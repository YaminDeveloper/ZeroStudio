package com.itsaky.androidide.app

import android.app.Application
import androidx.core.content.edit

/** Matrix 二阶段配置：插件开关 + 基础性能阈值。 */
data class MatrixApmConfig(
    val enableTrace: Boolean,
    val enableIo: Boolean,
    val enableResource: Boolean,
    val enableBattery: Boolean,
    val enableTraffic: Boolean,
    val enableSQLiteLint: Boolean,
    val issueRetentionDays: Int,
    val startupWarmUpMs: Long,
) {
  companion object {
    private const val PREFS = "matrix_apm"

    fun load(application: Application): MatrixApmConfig {
      val sp = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
      val defaults = MatrixApmConfig(true, true, true, true, true, true, 7, 20_000L)

      if (!sp.contains("init")) {
        sp.edit {
          putBoolean("init", true)
          putBoolean("enable_trace", defaults.enableTrace)
          putBoolean("enable_io", defaults.enableIo)
          putBoolean("enable_resource", defaults.enableResource)
          putBoolean("enable_battery", defaults.enableBattery)
          putBoolean("enable_traffic", defaults.enableTraffic)
          putBoolean("enable_sqlite", defaults.enableSQLiteLint)
          putInt("issue_retention_days", defaults.issueRetentionDays)
          putLong("startup_warmup_ms", defaults.startupWarmUpMs)
        }
      }

      return MatrixApmConfig(
          enableTrace = sp.getBoolean("enable_trace", defaults.enableTrace),
          enableIo = sp.getBoolean("enable_io", defaults.enableIo),
          enableResource = sp.getBoolean("enable_resource", defaults.enableResource),
          enableBattery = sp.getBoolean("enable_battery", defaults.enableBattery),
          enableTraffic = sp.getBoolean("enable_traffic", defaults.enableTraffic),
          enableSQLiteLint = sp.getBoolean("enable_sqlite", defaults.enableSQLiteLint),
          issueRetentionDays = sp.getInt("issue_retention_days", defaults.issueRetentionDays),
          startupWarmUpMs = sp.getLong("startup_warmup_ms", defaults.startupWarmUpMs),
      )
    }
  }
}
