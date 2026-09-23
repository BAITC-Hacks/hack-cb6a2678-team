"""One-time preservation of the original metrics before the ML tasks/refit."""
from pathlib import Path
import shutil
import pandas as pd
import numpy as np

root = Path(__file__).resolve().parents[1]
for site in ("turbine_1", "turbine_2"):
    source = root / "outputs/training" / site
    backup = root / "outputs/before_tasks" / site
    backup.mkdir(parents=True, exist_ok=True)
    for name in ("test_predictions.csv", "report.json", "evaluation_model.json"):
        if not (backup / name).exists():
            shutil.copy2(source / name, backup / name)
    predictions = pd.read_csv(source / "test_predictions.csv")
    predictions["p10"] = np.minimum(predictions.p10, predictions.forecast)
    predictions["p90"] = np.maximum(predictions.p90, predictions.forecast)
    predictions.to_csv(source / "test_predictions.csv", index=False)
