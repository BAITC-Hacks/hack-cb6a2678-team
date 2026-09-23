package com.ybkuanysh.backend.turbine

import com.ybkuanysh.backend.dto.Turbine
import org.springframework.stereotype.Component

class NotFoundException(message: String) : RuntimeException(message)

/** Турбины станции. Координаты — из ТЗ (ссылки Google Maps). */
@Component
class TurbineRegistry {

    val turbines = listOf(
        Turbine("t1", "Турбина 1", 43.645150, 78.535604),
        Turbine("t2", "Турбина 2", 43.643198, 78.538828),
    )

    fun requireTurbine(turbineId: String): Turbine =
        turbines.find { it.id == turbineId } ?: throw NotFoundException("Turbine not found: turbineId=$turbineId")
}
