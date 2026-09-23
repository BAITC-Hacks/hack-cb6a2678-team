from pathlib import Path
import hashlib
import importlib.metadata

import pytest
from packaging.markers import default_environment
from packaging.requirements import Requirement

ROOT = Path(__file__).resolve().parents[1]


def _check_runtime_dependencies(requirements):
    for line in requirements:
        requirement = Requirement(line)
        pins = list(requirement.specifier)
        assert len(pins) == 1 and pins[0].operator == "==" and "*" not in pins[0].version, line
        if requirement.marker is not None and not requirement.marker.evaluate():
            continue
        assert importlib.metadata.version(requirement.name) == pins[0].version


def test_runtime_dependencies_are_pinned_and_installed():
    requirements = (ROOT / "requirements.txt").read_text(encoding="utf-8").splitlines()
    assert any(line.startswith("pyarrow==") for line in requirements)
    _check_runtime_dependencies(requirements)


@pytest.mark.parametrize("sys_platform", ["linux", "darwin", "win32"])
def test_runtime_dependencies_respect_platform_markers(monkeypatch, sys_platform):
    environment = {**default_environment(), "sys_platform": sys_platform}
    monkeypatch.setattr("packaging.markers.default_environment", lambda: environment)
    checked = []

    def installed_version(name):
        checked.append(name)
        if name == "colorama" and sys_platform != "win32":
            raise importlib.metadata.PackageNotFoundError(name)
        return {"pyarrow": "24.0.0", "colorama": "0.4.6"}[name]

    monkeypatch.setattr(importlib.metadata, "version", installed_version)
    _check_runtime_dependencies(["pyarrow==24.0.0", "colorama==0.4.6; sys_platform == 'win32'"])
    assert checked == (["pyarrow", "colorama"] if sys_platform == "win32" else ["pyarrow"])


@pytest.mark.parametrize("requirement", ["pyarrow>=24.0.0", "pyarrow==24.*", "pyarrow"])
def test_runtime_dependencies_reject_unpinned_requirements(requirement):
    with pytest.raises(AssertionError):
        _check_runtime_dependencies([requirement])


@pytest.mark.parametrize("installed", [None, "0.0.0"])
def test_runtime_dependencies_reject_missing_or_wrong_versions(monkeypatch, installed):
    def installed_version(name):
        if installed is None:
            raise importlib.metadata.PackageNotFoundError(name)
        return installed

    monkeypatch.setattr(importlib.metadata, "version", installed_version)
    error = importlib.metadata.PackageNotFoundError if installed is None else AssertionError
    with pytest.raises(error):
        _check_runtime_dependencies(["pyarrow==24.0.0"])


def test_docker_contains_offline_artifacts():
    recipe = (ROOT / "Dockerfile").read_text()
    assert "python:3.12-slim" in recipe and "libgomp1" in recipe
    assert "COPY data ./data" in recipe and "COPY models ./models" in recipe
    assert "/health" in recipe


def _dataset_sha256(data):
    # Git меняет окончания строк при checkout; содержимое CSV должно остаться тем же.
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def test_datasets_are_canonical_and_unique():
    expected = {"turbine_1": "d82def7e56c0a1eed3f2f68eb29fd66e4299999921c9703720a880a5cf7d6703",
                "turbine_2": "16a844db949562290c96a86efa20178a71540ff6252275c003db4715edb9434a"}
    assert not list(ROOT.glob("Dataset*.csv"))
    for site, digest in expected.items():
        assert _dataset_sha256((ROOT / "data/raw" / f"{site}.csv").read_bytes()) == digest


@pytest.mark.parametrize("newline", [b"\n", b"\r\n"], ids=["LF", "CRLF"])
def test_dataset_checksum_accepts_line_endings_but_detects_changed_values(newline):
    csv = b"time,power\n2026-01-01T00:00:00Z,0.5\n"
    expected = hashlib.sha256(csv).hexdigest()
    checked_out = csv.replace(b"\n", newline)
    assert _dataset_sha256(checked_out) == expected
    assert _dataset_sha256(checked_out.replace(b"0.5", b"0.6")) != expected
