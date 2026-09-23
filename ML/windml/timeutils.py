"""UTC на границе API; старые местные поля сохраняются для совместимости."""
import pandas as pd

from .config import LOCAL_TZ


def utc_string(value, local_tz=LOCAL_TZ):
    stamp = pd.Timestamp(value)
    if stamp.tzinfo is None:
        stamp = stamp.tz_localize(local_tz)
    return stamp.tz_convert("UTC").isoformat(timespec="seconds").replace("+00:00", "Z")
