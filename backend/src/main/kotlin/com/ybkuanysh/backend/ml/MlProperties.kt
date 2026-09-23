package com.ybkuanysh.backend.ml

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("ml")
data class MlProperties(
    val baseUrl: String = "http://localhost:8000",
    /** Пояс, в котором ML выпускает прогноз (D 12:00) — пока ML не отдаёт local_tz сам. */
    val localTz: String = "Asia/Almaty",
    /** Час выпуска по местному времени — пока ML не отдаёт issued_at_utc сам. */
    val issueHourLocal: Int = 12,
)
