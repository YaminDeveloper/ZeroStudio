package com.itsaky.androidide.app

import android.app.Application
import org.slf4j.LoggerFactory

/**
 * Tencent Matrix 全量 APM 初始化器（反射方式，尽量兼容 Matrix 各版本 API）。
 */
object MatrixApmInitializer {

  private val log = LoggerFactory.getLogger(MatrixApmInitializer::class.java)

  fun init(application: Application) {
    runCatching {
          val matrixClass = Class.forName("com.tencent.matrix.Matrix")
          val builderClass = Class.forName("com.tencent.matrix.Matrix\$Builder")
          val pluginClass = Class.forName("com.tencent.matrix.plugin.Plugin")

          val builder =
              builderClass.getConstructor(Application::class.java).newInstance(application)

          val plugins =
              listOfNotNull(
                  buildPlugin(
                      pluginClass,
                      "com.tencent.matrix.trace.TracePlugin",
                      buildConfig("com.tencent.matrix.trace.config.TraceConfig")
                  ),
                  buildPlugin(
                      pluginClass,
                      "com.tencent.matrix.iocanary.IOCanaryPlugin",
                      buildConfig("com.tencent.matrix.iocanary.config.IOConfig")
                  ),
                  buildPlugin(
                      pluginClass,
                      "com.tencent.matrix.resource.ResourcePlugin",
                      buildConfig("com.tencent.matrix.resource.config.ResourceConfig")
                  ),
                  buildPlugin(
                      pluginClass,
                      "com.tencent.matrix.batterycanary.BatteryMonitorPlugin",
                      buildConfig("com.tencent.matrix.batterycanary.monitor.BatteryMonitorConfig")
                  ),
                  buildPlugin(
                      pluginClass,
                      "com.tencent.matrix.traffic.TrafficPlugin",
                      null
                  ),
                  buildPlugin(
                      pluginClass,
                      "com.tencent.matrix.sqlitelint.SQLiteLintPlugin",
                      buildConfig("com.tencent.matrix.sqlitelint.config.SQLiteLintConfig")
                  ),
              )

          val pluginMethod = builderClass.methods.firstOrNull { m ->
            m.name == "plugin" && m.parameterTypes.size == 1 &&
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

          log.info("Tencent Matrix initialized with {} plugins", plugins.size)
        }
        .onFailure { err ->
          log.warn("Matrix APM initialization skipped/failed", err)
        }
  }

  private fun buildConfig(configClassName: String): Any? {
    return runCatching {
          val builderClass = Class.forName("$configClassName\$Builder")
          val builder = builderClass.getDeclaredConstructor().newInstance()
          builderClass.methods.firstOrNull { it.name == "build" && it.parameterCount == 0 }
              ?.invoke(builder)
        }
        .recoverCatching {
          val configClass = Class.forName(configClassName)
          configClass.getDeclaredConstructor().newInstance()
        }
        .getOrNull()
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
}
