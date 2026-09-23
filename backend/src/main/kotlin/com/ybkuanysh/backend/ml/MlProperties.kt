package com.ybkuanysh.backend.ml

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("ml")
data class MlProperties(
    val baseUrl: String = "http://localhost:8000",
    /** Пояс, в котором ML выпускает прогноз (D 12:00) — пока ML не отдаёт local_tz сам. */
    val localTz: String = "Asia/Almaty",
    /** Час выпуска по местному времени — пока ML не отдаёт issued_at_utc сам. */
    val issueHourLocal: Int = 12,
    /** Прогнозы отложенного теста, пока ML не отдаёт GET /v1/evaluation: <dir>/<site>/test_predictions.csv. */
    val evaluationDir: String = "../ML/outputs/training",
    /** SCADA из датасета организаторов: <dir>/<site>.csv (10-минутные данные, местное время). */
    val scadaDir: String = "../ML/data/raw",
)
