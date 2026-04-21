package com.itsaky.androidide.app

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.slf4j.LoggerFactory

/** Matrix Issue 本地持久化（ndjson），用于二阶段全链路观测。 */
class MatrixIssueStore(private val application: Application) {

  private val log = LoggerFactory.getLogger(MatrixIssueStore::class.java)
  private val dir: File by lazy {
    File(application.filesDir, "matrix/issues").apply { mkdirs() }
  }

  fun pruneOld(days: Int) {
    runCatching {
          val expireMs = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
          dir.listFiles()?.forEach { file ->
            if (file.lastModified() < expireMs) {
              file.delete()
            }
          }
        }
        .onFailure { log.warn("Failed to prune Matrix issue files", it) }
  }

  fun append(plugin: String, content: String) {
    runCatching {
          val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
          val target = File(dir, "matrix-$date.ndjson")
          val row =
              "{\"ts\":${System.currentTimeMillis()},\"plugin\":\"${escape(plugin)}\",\"content\":\"${escape(content)}\"}\n"
          target.appendText(row)
        }
        .onFailure { log.warn("Failed writing Matrix issue", it) }
  }

  fun queryRecent(limit: Int = 200, pluginPrefix: String? = null): List<MatrixIssueRecord> {
    val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return emptyList()
    val out = mutableListOf<MatrixIssueRecord>()
    for (file in files) {
      if (out.size >= limit) break
      val lines = runCatching { file.readLines() }.getOrElse { emptyList() }
      for (line in lines.asReversed()) {
        if (out.size >= limit) break
        parseLine(line)?.let { rec ->
          if (pluginPrefix == null || rec.plugin.startsWith(pluginPrefix)) {
            out += rec
          }
        }
      }
    }
    return out
  }

  fun summarizeByPlugin(limit: Int = 1000): Map<String, Int> {
    return queryRecent(limit = limit).groupingBy { it.plugin }.eachCount()
  }

  private fun parseLine(line: String): MatrixIssueRecord? {
    return runCatching {
          val obj = JSONObject(line)
          val ts = obj.optLong("ts", -1L)
          val plugin = obj.optString("plugin", "")
          val content = obj.optString("content", "")
          if (ts <= 0L || plugin.isBlank()) return null
          MatrixIssueRecord(ts, plugin, content)
        }
        .getOrNull()
  }

  fun export(records: List<MatrixIssueRecord>, target: File): File {
    target.parentFile?.mkdirs()
    val content =
        records.joinToString(separator = "\n") {
          "{\"ts\":${it.ts},\"plugin\":\"${escape(it.plugin)}\",\"content\":\"${escape(it.content)}\"}"
        }
    target.writeText(content)
    return target
  }

  private fun escape(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
  }
}
