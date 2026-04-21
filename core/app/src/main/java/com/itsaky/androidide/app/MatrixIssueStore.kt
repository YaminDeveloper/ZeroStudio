package com.itsaky.androidide.app

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

  private fun escape(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
  }
}
