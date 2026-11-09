package com.monitor.usagetracker.data.export

import android.content.Context
import com.monitor.usagetracker.data.local.entity.AppMetadataEntity
import com.monitor.usagetracker.data.local.entity.AppUsageSessionEntity
import com.monitor.usagetracker.domain.model.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DataExportManager(private val context: Context) {

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    suspend fun exportSessionsToFile(
        sessions: List<AppUsageSessionEntity>,
        metadataMap: Map<String, AppMetadataEntity>,
        startDateKey: String,
        endDateKey: String,
        format: ExportFormat
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.filesDir, "exports").apply {
                if (!exists()) mkdirs()
            }
            val extension = if (format == ExportFormat.CSV) "csv" else "json"
            val fileName = "app_usage_${startDateKey}_to_${endDateKey}.$extension"
            val outputFile = File(exportDir, fileName)

            FileOutputStream(outputFile).use { fos ->
                if (format == ExportFormat.CSV) {
                    fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                }
                BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8)).use { writer ->
                    when (format) {
                        ExportFormat.CSV -> writeCsv(writer, sessions, metadataMap)
                        ExportFormat.JSON -> writeJson(writer, sessions, metadataMap)
                    }
                }
            }
            outputFile
        }
    }

    private fun writeCsv(
        writer: BufferedWriter,
        sessions: List<AppUsageSessionEntity>,
        metadataMap: Map<String, AppMetadataEntity>
    ) {
        writer.write("日期,应用名称,包名,是否系统应用,启动时间,结束时间,持续时长(秒),是否前台进行中,是否异常闭合估算\n")
        for (session in sessions) {
            val meta = metadataMap[session.packageName]
            val appName = escapeCsvField(meta?.appName ?: session.packageName)
            val isSystem = if (meta?.isSystemApp == true) "是" else "否"
            val startFormatted = dateTimeFormatter.format(Instant.ofEpochMilli(session.startTimeMs))
            val endFormatted = dateTimeFormatter.format(Instant.ofEpochMilli(session.endTimeMs))
            val durationSeconds = session.durationMs / 1000L
            val ongoingStr = if (session.isOngoing) "是" else "否"
            val estimatedStr = if (session.isEstimatedEnd) "是" else "否"

            writer.write(
                "${session.dateKey},$appName,${session.packageName},$isSystem," +
                    "$startFormatted,$endFormatted,$durationSeconds,$ongoingStr,$estimatedStr\n"
            )
        }
        writer.flush()
    }

    private fun writeJson(
        writer: BufferedWriter,
        sessions: List<AppUsageSessionEntity>,
        metadataMap: Map<String, AppMetadataEntity>
    ) {
        val jsonArray = JSONArray()
        for (session in sessions) {
            val meta = metadataMap[session.packageName]
            val obj = JSONObject().apply {
                put("sessionId", session.sessionId)
                put("dateKey", session.dateKey)
                put("packageName", session.packageName)
                put("appName", meta?.appName ?: session.packageName)
                put("isSystemApp", meta?.isSystemApp ?: false)
                put("startTimeMs", session.startTimeMs)
                put("startTimeFormatted", dateTimeFormatter.format(Instant.ofEpochMilli(session.startTimeMs)))
                put("endTimeMs", session.endTimeMs)
                put("endTimeFormatted", dateTimeFormatter.format(Instant.ofEpochMilli(session.endTimeMs)))
                put("durationMs", session.durationMs)
                put("durationSeconds", session.durationMs / 1000L)
                put("isOngoing", session.isOngoing)
                put("isEstimatedEnd", session.isEstimatedEnd)
            }
            jsonArray.put(obj)
        }
        writer.write(jsonArray.toString(2))
        writer.flush()
    }

    private fun escapeCsvField(raw: String): String {
        val needsQuotes = raw.contains(',') || raw.contains('"') || raw.contains('\n')
        if (!needsQuotes) return raw
        return "\"${raw.replace("\"", "\"\"")}\""
    }
}
