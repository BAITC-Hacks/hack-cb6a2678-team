from pathlib import Path
import importlib.metadata

ROOT = Path(__file__).resolve().parents[1]


def test_runtime_dependencies_are_pinned_and_installed():
    requirements = (ROOT / "requirements.txt").read_text().splitlines()
    assert any(line.startswith("pyarrow==") for line in requirements)
    for line in requirements:
        name, version = line.split(";", 1)[0].strip().split("==")
        assert importlib.metadata.version(name) == version


def test_docker_contains_offline_artifacts():
    recipe = (ROOT / "Dockerfile").read_text()
    assert "python:3.12-slim" in recipe and "libgomp1" in recipe
    assert "COPY data ./data" in recipe and "COPY models ./models" in recipe
    assert "/health" in recipe


def test_datasets_are_canonical_and_unique():
    import hashlib
    expected = {"turbine_1": "c4c341582fb2dd348b7187f0128cff265fe055f469413871ebb5db50eef58b5b",
                "turbine_2": "820578cd18bb557cd30c2e102f3ae5a386dfc6c489a5a15743339c2b017305e5"}
    assert not list(ROOT.glob("Dataset*.csv"))
    for site, digest in expected.items():
        assert hashlib.sha256((ROOT / "data/raw" / f"{site}.csv").read_bytes()).hexdigest() == digest
