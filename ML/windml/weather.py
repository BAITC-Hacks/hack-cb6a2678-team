"""Получение прогнозов погоды (NWP) из открытых источников без утечки будущего.

Источник по умолчанию — Open-Meteo Previous Runs API
(https://open-meteo.com/en/docs/previous-runs-api): для каждого часа хранится
значение, которое модель прогнозировала N суток назад (`<var>_previous_dayN`).
Это ровно «архивный прогноз, доступный на момент прогнозирования», который
требует ТЗ, а не фактическая погода (реанализ), ставшая известной позже.

Провайдеры:
  * PreviousRunsProvider — архив прогнозов (бэктест, обучение)          [основной]
  * LiveForecastProvider — свежий прогноз (боевой режим «сегодня»)
  * MockProvider         — синтетика из SCADA только для офлайн-тестов кода
Все провайдеры возвращают DataFrame с UTC-индексом и колонками вида
`<var>@pd<N>` (N — «сколько суток назад выпущен прогноз», 0 = последний).
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import requests

from .config import CFG, LOCAL_TZ, SITES, WEATHER_CACHE_SITES, lead_to_previous_day

log = logging.getLogger(__name__)

PREV_RUNS_URL = "https://previous-runs-api.open-meteo.com/v1/forecast"
LIVE_URL = "https://api.open-meteo.com/v1/forecast"


class WeatherUnavailable(RuntimeError):
    pass


def _get_json(url: str, params: dict) -> dict:
    if os.environ.get("WINDML_OFFLINE") == "1":
        raise WeatherUnavailable("Weather is not cached and WINDML_OFFLINE=1")
    last = None
    for attempt in range(CFG.http_retries):
        try:
            r = requests.get(url, params=params, timeout=CFG.http_timeout)
            if r.status_code == 400:            # неверная переменная/модель — повтор бесполезен
                try:
                    reason = r.json().get("reason", r.text)
                except ValueError:
                    reason = r.text
                raise WeatherUnavailable(reason[:300])
            if r.status_code == 429:            # лимит бесплатного API
                time.sleep(20 * (attempt + 1))
                continue
            r.raise_for_status()
            return r.json()
        except (requests.ConnectionError, requests.Timeout, requests.HTTPError) as e:
            last = e
            time.sleep(2 ** attempt)
    raise WeatherUnavailable(f"{url}: {last}")


def _parse_hourly(js: dict, rename) -> pd.DataFrame:
    h = js.get("hourly") or {}
    if "time" not in h:
        raise WeatherUnavailable("no hourly block in response")
    idx = pd.to_datetime(h.pop("time"), utc=True)
    df = pd.DataFrame({rename(k): v for k, v in h.items()}, index=idx).astype(float)
    df.index.name = "time_utc"
    return df.dropna(axis=1, how="all")


# ----------------------------------------------------------------- providers
class PreviousRunsProvider:
    name = "open-meteo-previous-runs"

    def __init__(self, site: str = "turbine_1", cache_dir: Path | None = None):
        self.site = SITES[site]
        self.cache_dir = Path(cache_dir or CFG.cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _fetch_chunk(self, model, start: date, end: date, pdays, variables) -> pd.DataFrame:
        hourly = [f"{v}_previous_day{n}" for v in variables for n in pdays]
        params = dict(latitude=self.site.lat, longitude=self.site.lon, models=model,
                      hourly=",".join(hourly), start_date=start.isoformat(),
                      end_date=end.isoformat(), wind_speed_unit="ms", timezone="GMT")
        js = _get_json(PREV_RUNS_URL, params)

        def rename(k):
            v, n = k.rsplit("_previous_day", 1)
            return f"{v}@pd{n}"
        return _parse_hourly(js, rename)

    def fetch(self, model: str, start: date, end: date, pdays=(1, 2, 3)) -> pd.DataFrame:
        """Архив прогнозов модели `model` за [start, end] (даты UTC), с кэшем по месяцам."""
        frames = []
        cur = date(start.year, start.month, 1)
        while cur <= end:
            nxt = (pd.Timestamp(cur) + pd.offsets.MonthBegin(1)).date()
            c_end = min(nxt - timedelta(days=1), end)
            complete = c_end < (datetime.now(timezone.utc).date() - timedelta(days=1))
            cache_site = WEATHER_CACHE_SITES.get(self.site.name, self.site.name) if model in CFG.nwp_models else self.site.name
            key = f"prev_{cache_site}_{model}_{cur:%Y%m}_{'-'.join(map(str, pdays))}.csv"
            fp = self.cache_dir / key
            if fp.exists() and complete:
                df = pd.read_csv(fp, index_col=0, parse_dates=True)
            else:
                f_end = nxt - timedelta(days=1) if complete else c_end
                try:
                    df = self._fetch_chunk(model, cur, f_end, pdays, CFG.hourly_vars)
                except WeatherUnavailable as e:
                    # часть переменных может отсутствовать в архиве этой модели →
                    # запрашиваем по одной и берём всё, что доступно
                    log.warning("%s %s: full var set failed (%s) → per-variable", model, cur, e)
                    parts = []
                    for v in CFG.hourly_vars:
                        try:
                            parts.append(self._fetch_chunk(model, cur, f_end, pdays, [v]))
                        except WeatherUnavailable:
                            log.info("%s: variable %s unavailable", model, v)
                    if not parts:
                        raise
                    df = pd.concat(parts, axis=1)
                if complete:
                    df.to_csv(fp)
            frames.append(df)
            cur = nxt
        out = pd.concat(frames).sort_index()
        out.index = pd.to_datetime(out.index, utc=True)
        s, e = pd.Timestamp(start, tz="UTC"), pd.Timestamp(end, tz="UTC") + pd.Timedelta(hours=23)
        return out.loc[s:e]


class LiveForecastProvider:
    """Боевой режим: последний доступный прогноз (для выпуска «сегодня»)."""
    name = "open-meteo-live"

    def __init__(self, site: str = "turbine_1"):
        self.site = SITES[site]

    def fetch(self, model: str, start: date, end: date, pdays=(0,)) -> pd.DataFrame:
        params = dict(latitude=self.site.lat, longitude=self.site.lon, models=model,
                      hourly=",".join(CFG.hourly_vars), start_date=start.isoformat(),
                      end_date=end.isoformat(), wind_speed_unit="ms", timezone="GMT")
        df = _parse_hourly(_get_json(LIVE_URL, params), lambda k: k)
        # живой прогноз подставляем во все запрошенные pdN
        return pd.concat({n: df for n in pdays}, axis=1).pipe(
            lambda d: d.set_axis([f"{v}@pd{n}" for n, v in d.columns], axis=1))


class MockProvider:
    """ТОЛЬКО для тестов без интернета: «прогноз» = фактический ветер SCADA,
    пересчитанный на 100 м + шум, растущий с заблаговременностью. Для отчёта
    и сабмита не использовать!"""
    name = "mock"

    def __init__(self, hourly_scada: pd.DataFrame, seed: int = 0):
        idx = hourly_scada.index.tz_localize(LOCAL_TZ, ambiguous="NaT", nonexistent="NaT").tz_convert("UTC")
        self.obs = hourly_scada.set_axis(idx)[["ws_obs", "temp_obs"]]
        self.obs = self.obs[self.obs.index.notna()]
        self.obs = self.obs[~self.obs.index.duplicated()]
        self.seed = seed

    def fetch(self, model: str, start: date, end: date, pdays=(1, 2, 3)) -> pd.DataFrame:
        s, e = pd.Timestamp(start, tz="UTC"), pd.Timestamp(end, tz="UTC") + pd.Timedelta(hours=23)
        idx = pd.date_range(s, e, freq="1h")
        o = self.obs.reindex(idx).interpolate(limit=6)
        rng = np.random.default_rng(abs(hash((model, str(start)))) % 2**32 + self.seed)
        bias = {"ecmwf_ifs025": 1.0, "gfs_seamless": 1.15, "icon_seamless": 0.9}.get(model, 1.0)
        out = {}
        for n in pdays:
            noise = rng.normal(0, 0.8 + 0.4 * n, len(idx))
            shift = int(rng.integers(-n, n + 1))
            ws = (o["ws_obs"].shift(shift) * 0.85 * bias + noise).clip(0.2)
            out[f"wind_speed_10m@pd{n}"] = ws
            out[f"wind_speed_100m@pd{n}"] = ws * 1.35
            out[f"wind_direction_10m@pd{n}"] = rng.uniform(0, 360, len(idx))
            out[f"temperature_2m@pd{n}"] = o["temp_obs"] + rng.normal(0, 1.5, len(idx))
            out[f"surface_pressure@pd{n}"] = 900 + rng.normal(0, 5, len(idx))
        df = pd.DataFrame(out, index=idx)
        df.index.name = "time_utc"
        return df


def get_provider(kind: str = "previous_runs", site: str = "turbine_1", **kw):
    if kind == "previous_runs":
        return PreviousRunsProvider(site, **kw)
    if kind == "live":
        return LiveForecastProvider(site)
    if kind == "mock":
        return MockProvider(**kw)
    raise ValueError(kind)


# ------------------------------------------------------------- assembling
def select_leads(raw: dict[str, pd.DataFrame], targets_local: pd.DatetimeIndex,
                 lead_days: np.ndarray, pd_map) -> pd.DataFrame:
    """Для каждого целевого часа берёт колонки нужного previous_day.

    raw        — {model: df(<var>@pd<N>, UTC-index)}
    pd_map     — функция lead_day → N
    Возвращает DataFrame(index=targets_local) с колонками <model>__<var>.
    """
    t_utc = targets_local.tz_localize(LOCAL_TZ, ambiguous="NaT", nonexistent="NaT").tz_convert("UTC")
    out = pd.DataFrame(index=targets_local)
    for model, df in raw.items():
        vars_ = sorted({c.split("@")[0] for c in df.columns})
        for v in vars_:
            col = np.full(len(targets_local), np.nan)
            for ld in np.unique(lead_days):
                c = f"{v}@pd{pd_map(int(ld))}"
                if c not in df.columns:
                    continue
                m = lead_days == ld
                col[m] = df[c].reindex(t_utc[m]).to_numpy()
            out[f"{model}__{v}"] = col
    return out


def target_window(issue_date, horizon_days: int | None = None) -> tuple[pd.DatetimeIndex, np.ndarray]:
    """Целевые часы (местное время, naive) и lead_day для выпуска в день D."""
    horizon_days = horizon_days or CFG.horizon_days
    d = pd.Timestamp(issue_date).normalize()
    idx = pd.date_range(d + pd.Timedelta(days=1), periods=24 * horizon_days, freq="1h")
    lead = ((idx.normalize() - d).days).to_numpy()
    return idx, lead


def fetch_issue_weather(issue_date, provider=None, models=None, horizon_days=None,
                        mode: str | None = None) -> tuple[pd.DataFrame, dict]:
    """NWP-признаки для одного выпуска прогноза + метаданные (для агента)."""
    provider = provider or PreviousRunsProvider()
    models = models or CFG.nwp_models
    idx, lead = target_window(issue_date, horizon_days)
    live = isinstance(provider, LiveForecastProvider)
    pd_map = (lambda ld: 0) if live else (lambda ld: lead_to_previous_day(ld, mode))
    pdays = tuple(sorted({pd_map(int(l)) for l in np.unique(lead)}))
    t_utc = idx.tz_localize(LOCAL_TZ, ambiguous="NaT", nonexistent="NaT").tz_convert("UTC")
    issued = (pd.Timestamp(issue_date).normalize() + pd.Timedelta(hours=CFG.issue_hour)).tz_localize(LOCAL_TZ).tz_convert("UTC")
    # Previous Runs задаёт фиксированную заблаговременность, не точный ID запуска.
    # Поэтому это верхняя граница выпуска, а не выдуманная метка конкретного run.
    bound = max(t - pd.Timedelta(days=pd_map(int(ld))) for t, ld in zip(t_utc, lead)) if not live else None
    if bound is not None and bound > issued:
        raise WeatherUnavailable("Weather issuance bound exceeds the forecast issue time")
    if live and pd.Timestamp.now(tz="UTC") > issued:
        raise WeatherUnavailable("Live weather cannot prove availability at the requested issue time")
    start, end = t_utc.min().date(), t_utc.max().date()
    raw, errors = {}, {}
    for m in models:
        try:
            raw[m] = provider.fetch(m, start, end, pdays=pdays)
        except Exception as e:  # noqa: BLE001 — модель недоступна, работаем с остальными
            errors[m] = str(e)[:200]
    if not raw:
        raise WeatherUnavailable(f"no NWP model available: {errors}")
    if live:
        bound = pd.Timestamp.now(tz="UTC")
        if bound > issued:
            raise WeatherUnavailable("Live weather cannot prove availability at the requested issue time")
    nwp = select_leads(raw, idx, lead, pd_map)
    nwp.insert(0, "lead_day", lead)
    meta = {
        "issue_date": str(pd.Timestamp(issue_date).date()),
        "issue_time_local": f"{pd.Timestamp(issue_date).date()} {CFG.issue_hour:02d}:00",
        "source": provider.name,
        "leakage_mode": mode or CFG.leakage_mode,
        "weather_issued_before_utc": bound.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "previous_days_used": {int(l): pd_map(int(l)) for l in np.unique(lead)},
        "models_ok": list(raw), "models_failed": errors,
        "target_start": str(idx.min()), "target_end": str(idx.max()),
        "n_hours": len(idx),
        "weather_hash": weather_hash(nwp),
    }
    return nwp, meta


def fetch_training_weather(start: str, end: str, provider=None, models=None,
                           mode: str | None = None) -> pd.DataFrame:
    """Стек признаков для обучения: каждый час × lead_day ∈ {1..horizon}."""
    provider = provider or PreviousRunsProvider()
    models = models or CFG.nwp_models
    hours = pd.date_range(pd.Timestamp(start), pd.Timestamp(end) + pd.Timedelta(hours=23), freq="1h")
    leads = range(1, CFG.horizon_days + 1)
    pdays = tuple(sorted({lead_to_previous_day(l, mode) for l in leads}))
    t_utc = hours.tz_localize(LOCAL_TZ, ambiguous="NaT", nonexistent="NaT").tz_convert("UTC")
    raw = {}
    for m in models:
        try:
            raw[m] = provider.fetch(m, t_utc.min().date(), t_utc.max().date(), pdays=pdays)
            log.info("training NWP %s: %d rows", m, len(raw[m]))
        except Exception as e:  # noqa: BLE001
            log.warning("model %s skipped: %s", m, e)
    if not raw:
        raise WeatherUnavailable("no NWP data for training")
    parts = []
    for l in leads:
        df = select_leads(raw, hours, np.full(len(hours), l), lambda ld: lead_to_previous_day(ld, mode))
        df.insert(0, "lead_day", l)
        parts.append(df)
    return pd.concat(parts)


def weather_hash(nwp: pd.DataFrame) -> str:
    """Отпечаток входных погодных данных — агент сравнивает его, чтобы понять,
    обновился ли прогноз погоды и нужен ли повторный расчёт."""
    arr = nwp.select_dtypes("number").round(2).fillna(-999).to_numpy()
    return hashlib.sha256(arr.tobytes() + json.dumps(list(nwp.columns)).encode()).hexdigest()[:16]
