package com.itsaky.androidide.app

import android.app.Application

/** 二阶段后半段：业务模块级自定义埋点。 */
object MatrixApmTracker {

  @Volatile private var issueStore: MatrixIssueStore? = null

  fun init(application: Application) {
    if (issueStore == null) {
      issueStore = MatrixIssueStore(application)
    }
  }

  fun reportModuleEvent(module: String, event: String, costMs: Long? = null, extra: String? = null) {
    val payload =
        buildString {
          append("event=").append(event)
          if (costMs != null) append(",costMs=").append(costMs)
          if (!extra.isNullOrBlank()) append(",extra=").append(extra)
        }
    issueStore?.append("module:$module", payload)
  }

  fun latestModuleEvents(limit: Int = 200): List<MatrixIssueRecord> {
    return issueStore?.queryRecent(limit = limit, pluginPrefix = "module:") ?: emptyList()
  }
}
