package com.ybkuanysh.backend.cycle

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Циклы агента: последние — в памяти (в т.ч. идущие, для поллинга), завершённые — ещё и в
 * `<storeDir>/<turbineId>/<issueDate>.json`. Файлы коммитятся: так жюри видит работу агента без LLM и ML.
 */
@Component
class CycleStore(private val props: CycleProperties, private val mapper: JsonMapper) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val records = ConcurrentHashMap<String, CycleRecord>()

    fun save(record: CycleRecord, persist: Boolean) {
        records[key(record.turbineId, record.issueDate)] = record
        val file = file(record.turbineId, record.issueDate) ?: return
        if (persist) {
            Files.createDirectories(file.parent)
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record))
        }
    }

    fun find(turbineId: String, issueDate: LocalDate): CycleRecord? =
        records[key(turbineId, issueDate)] ?: file(turbineId, issueDate)?.takeIf { Files.exists(it) }?.let { f ->
            runCatching { mapper.readValue(Files.readString(f), CycleRecord::class.java) }
                .onFailure { log.warn("Cannot read cycle {}: {}", f, it.message) }
                .getOrNull()
                ?.also { records[key(turbineId, issueDate)] = it }
        }

    private fun key(turbineId: String, issueDate: LocalDate) = "$turbineId|$issueDate"

    private fun file(turbineId: String, issueDate: LocalDate): Path? =
        props.storeDir.takeIf { it.isNotBlank() }?.let { Path.of(it, turbineId, "$issueDate.json") }
}
