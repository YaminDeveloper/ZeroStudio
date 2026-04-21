package com.itsaky.androidide.app

import android.app.Application
import com.itsaky.androidide.BuildConfig
import java.lang.reflect.Proxy
import org.slf4j.LoggerFactory

/**
 * Tencent Matrix 全量 APM 初始化器（反射方式，尽量兼容 Matrix 各版本 API）。
 */
object MatrixApmInitializer {

  private val log = LoggerFactory.getLogger(MatrixApmInitializer::class.java)

  private data class PluginSpec(
      val enabled: Boolean,
      val pluginClassName: String,
      val configClassName: String? = null,
  )

  fun init(application: Application) {
    if (!BuildConfig.DEBUG) {
      log.info("Matrix APM disabled for non-debug build")
      return
    }
    val apmConfig = MatrixApmConfig.load(application)
    val issueStore = MatrixIssueStore(application)
    issueStore.pruneOld(apmConfig.issueRetentionDays)

    runCatching {
          val matrixClass = Class.forName("com.tencent.matrix.Matrix")
          val builderClass = Class.forName("com.tencent.matrix.Matrix\$Builder")
          val pluginClass = Class.forName("com.tencent.matrix.plugin.Plugin")

          val builder =
              builderClass.getConstructor(Application::class.java).newInstance(application)

          createPluginListener(issueStore)?.let { listener ->
            builderClass.methods
                .firstOrNull { it.name == "pluginListener" && it.parameterTypes.size == 1 }
                ?.invoke(builder, listener)
          }

          val specs =
              listOf(
                  PluginSpec(apmConfig.enableTrace, "com.tencent.matrix.trace.TracePlugin", "com.tencent.matrix.trace.config.TraceConfig"),
                  PluginSpec(apmConfig.enableIo, "com.tencent.matrix.iocanary.IOCanaryPlugin", "com.tencent.matrix.iocanary.config.IOConfig"),
                  PluginSpec(apmConfig.enableResource, "com.tencent.matrix.resource.ResourcePlugin", "com.tencent.matrix.resource.config.ResourceConfig"),
                  PluginSpec(apmConfig.enableBattery, "com.tencent.matrix.batterycanary.BatteryMonitorPlugin", "com.tencent.matrix.batterycanary.monitor.BatteryMonitorConfig"),
                  PluginSpec(apmConfig.enableTraffic, "com.tencent.matrix.traffic.TrafficPlugin", null),
                  PluginSpec(apmConfig.enableSQLiteLint, "com.tencent.matrix.sqlitelint.SQLiteLintPlugin", "com.tencent.matrix.sqlitelint.config.SQLiteLintConfig"),
              )

          val plugins =
              specs.filter { it.enabled }.mapNotNull { spec ->
                val config = spec.configClassName?.let { buildConfig(it, apmConfig) }
                buildPlugin(pluginClass, spec.pluginClassName, config)
              }

          val pluginMethod = builderClass.methods.firstOrNull { m ->
            m.name == "plugin" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0].isAssignableFrom(pluginClass)
          }

          plugins.forEach { plugin ->
            try {
              pluginMethod?.invoke(builder, plugin)
              log.info("Matrix plugin loaded: {}", plugin.javaClass.name)
            } catch (t: Throwable) {
              log.warn("Failed to attach Matrix plugin: {}", plugin.javaClass.name, t)
            }
          }

          val matrixInstance = builderClass.getMethod("build").invoke(builder)
          matrixClass.getMethod("init", matrixInstance.javaClass).invoke(null, matrixInstance)

          val matrix = matrixClass.getMethod("with").invoke(null)
          matrixClass.getMethod("startAllPlugins").invoke(matrix)

          log.info("Tencent Matrix phase-2 initialized with {} plugins", plugins.size)
        }
        .onFailure { err ->
          log.warn("Matrix APM initialization skipped/failed", err)
        }
  }

  private fun buildConfig(configClassName: String, apmConfig: MatrixApmConfig): Any? {
    return runCatching {
          val builderClass = Class.forName("$configClassName\$Builder")
          val builder = builderClass.getDeclaredConstructor().newInstance()
          applyTuning(builder, apmConfig)
          builderClass.methods
              .firstOrNull { it.name == "build" && it.parameterCount == 0 }
              ?.invoke(builder)
        }
        .recoverCatching {
          val configClass = Class.forName(configClassName)
          configClass.getDeclaredConstructor().newInstance()
        }
        .getOrNull()
  }

  private fun applyTuning(builder: Any, apmConfig: MatrixApmConfig) {
    builder.javaClass.methods.forEach { method ->
      try {
        if (method.parameterCount != 1) return@forEach
        val paramType = method.parameterTypes[0]
        when {
          method.name.contains("warm", ignoreCase = true) &&
              (paramType == Long::class.javaPrimitiveType || paramType == java.lang.Long::class.java) -> {
            method.invoke(builder, apmConfig.startupWarmUpMs)
          }

          method.name.contains("debug", ignoreCase = true) &&
              (paramType == Boolean::class.javaPrimitiveType || paramType == java.lang.Boolean::class.java) -> {
            method.invoke(builder, false)
          }
        }
      } catch (_: Throwable) {
        // keep best-effort tuning only
      }
    }
  }

  private fun buildPlugin(pluginBaseClass: Class<*>, pluginClassName: String, config: Any?): Any? {
    return runCatching {
          val pluginClass = Class.forName(pluginClassName)

          if (config != null) {
            pluginClass.constructors.firstOrNull { ctor ->
              ctor.parameterTypes.size == 1 &&
                  ctor.parameterTypes[0].isAssignableFrom(config.javaClass)
            }?.newInstance(config)
          } else {
            null
          }
              ?: pluginClass.getDeclaredConstructor().newInstance()
        }
        .onFailure { e -> log.warn("Matrix plugin unavailable: {}", pluginClassName, e) }
        .getOrNull()
        ?.takeIf { pluginBaseClass.isInstance(it) }
  }

  private fun createPluginListener(issueStore: MatrixIssueStore): Any? {
    return runCatching {
          val listenerClass = Class.forName("com.tencent.matrix.plugin.PluginListener")
          if (!listenerClass.isInterface) return@runCatching null

          Proxy.newProxyInstance(
              listenerClass.classLoader,
              arrayOf(listenerClass),
          ) { _, method, args ->
            if (method?.name == "onReportIssue" && !args.isNullOrEmpty()) {
              val issue = args[0]
              val pluginName = extractIssuePluginName(issue)
              val payload = issue?.toString() ?: "null"
              issueStore.append(pluginName, payload)
            }
            null
          }
        }
        .getOrNull()
  }

  private fun extractIssuePluginName(issue: Any?): String {
    if (issue == null) return "unknown"
    return runCatching {
          issue.javaClass.methods
              .firstOrNull { it.name.equals("getTag", ignoreCase = true) && it.parameterCount == 0 }
              ?.invoke(issue)
              ?.toString()
              ?.takeIf { it.isNotBlank() }
        }
        .getOrNull() ?: issue.javaClass.simpleName
  }
}
