package com.itsaky.androidide.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import dalvik.system.DexFile
import com.itsaky.androidide.utils.executioncommand.TermuxCommand
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class EditorClassArtifactStat(
    val classFileCount: Int,
    val clazzFileCount: Int,
    val kotlinFileCount: Int,
)

data class EditorDexClassStat(
    val totalClassCount: Int,
    val appPackageClassCount: Int,
)

data class EditorProcessApmSnapshot(
    val timestampMs: Long,
    val cpuUsagePercent: Double,
    val processRssMb: Double,
    val processPssMb: Double,
    val javaHeapUsedMb: Double,
    val javaHeapMaxMb: Double,
    val nativeHeapMb: Double,
    val threadCount: Int,
    val openFdCount: Int,
    val gcCount: Long,
    val gcTimeMs: Long,
    val appUptimeMs: Long,
    val dexClassStat: EditorDexClassStat,
    val classArtifactStat: EditorClassArtifactStat,
    val hotClassStats: List<EditorHotClassStat>,
    val termuxSubsystemStats: List<EditorSubsystemStat>,
    val healthAlerts: List<String>,
)

data class EditorHotClassStat(
    val className: String,
    val calls: Int,
    val totalCpuMs: Double,
    val avgCpuMs: Double,
    val totalMemMb: Double,
    val avgMemMb: Double,
    val peakMemMb: Double,
)

data class EditorSubsystemStat(
    val name: String,
    val processCount: Int,
    val totalCpuPercent: Double,
    val totalRssMb: Double,
)

/** Non-intrusive process-level APM monitor for editor bottom sheet dashboard. */
class EditorProcessApmMonitor(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  fun stream(intervalMs: Long = 1000L): Flow<EditorProcessApmSnapshot> = flow {
    var previous = readCpuStat()
    var previousProcessCpuMs = Process.getElapsedCpuTime()
    var previousPssMb = readProcessPssMb()
    val dexStat = readDexClassStat()
    var classArtifactStat = scanClassArtifacts()
    val classActivityTracker = ClassActivityTracker(appContext.packageName)
    var termuxSubsystemStats: List<EditorSubsystemStat> = emptyList()
    var ticks = 0

    while (true) {
      val current = readCpuStat()
      val cpuUsage = calcCpuUsage(previous, current)
      previous = current
      val processPssMb = readProcessPssMb()
      val processCpuMs = Process.getElapsedCpuTime()
      val cpuDeltaMs = max(0L, processCpuMs - previousProcessCpuMs).toDouble()
      val pssDeltaMb = (processPssMb - previousPssMb).coerceAtLeast(0.0)
      previousProcessCpuMs = processCpuMs
      previousPssMb = processPssMb

      if (ticks % 15 == 0) {
        classArtifactStat = scanClassArtifacts()
      }
      ticks++

      val hotClassStats = classActivityTracker.sample(cpuDeltaMs = cpuDeltaMs, memDeltaMb = pssDeltaMb)
      if (ticks % 5 == 0) {
        termuxSubsystemStats = readTermuxSubsystemStats()
      }
      val javaHeapUsedMb = readJavaHeapUsedMb()
      val javaHeapMaxMb = readJavaHeapMaxMb()
      val gcTimeMs = readGcTimeMs()
      val alerts =
          buildHealthAlerts(
              cpuUsagePercent = cpuUsage,
              javaHeapUsedMb = javaHeapUsedMb,
              javaHeapMaxMb = javaHeapMaxMb,
              gcTimeMs = gcTimeMs,
          )

      emit(
          EditorProcessApmSnapshot(
              timestampMs = System.currentTimeMillis(),
              cpuUsagePercent = cpuUsage,
              processRssMb = readProcessRssMb(),
              processPssMb = processPssMb,
              javaHeapUsedMb = javaHeapUsedMb,
              javaHeapMaxMb = javaHeapMaxMb,
              nativeHeapMb = readNativeHeapMb(),
              threadCount = Thread.getAllStackTraces().size,
              openFdCount = readOpenFdCount(),
              gcCount = readGcCount(),
              gcTimeMs = gcTimeMs,
              appUptimeMs = SystemClock.elapsedRealtime(),
              dexClassStat = dexStat,
              classArtifactStat = classArtifactStat,
              hotClassStats = hotClassStats,
              termuxSubsystemStats = termuxSubsystemStats,
              healthAlerts = alerts,
          )
      )
      delay(intervalMs)
    }
  }.flowOn(ioDispatcher)

  private fun readProcessPssMb(): Double {
    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
    return (info?.totalPss?.toDouble() ?: 0.0) / 1024.0
  }

  private fun readJavaHeapUsedMb(): Double {
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()).toDouble() / 1024.0 / 1024.0
  }

  private fun readJavaHeapMaxMb(): Double = Runtime.getRuntime().maxMemory().toDouble() / 1024.0 / 1024.0

  private fun readNativeHeapMb(): Double = Debug.getNativeHeapAllocatedSize().toDouble() / 1024.0 / 1024.0

  private fun readOpenFdCount(): Int = File("/proc/self/fd").list()?.size ?: 0

  private fun readGcCount(): Long = Debug.getRuntimeStats()["art.gc.gc-count"]?.toLongOrNull() ?: 0L

  private fun readGcTimeMs(): Long = Debug.getRuntimeStats()["art.gc.gc-time"]?.toLongOrNull() ?: 0L

  private fun readProcessRssMb(): Double {
    val statm = File("/proc/self/statm")
    if (!statm.exists()) return 0.0
    val pages = statm.readText().trim().split(" ").getOrNull(1)?.toLongOrNull() ?: return 0.0
    return (pages * 4096L).toDouble() / 1024.0 / 1024.0
  }

  private fun readDexClassStat(): EditorDexClassStat {
    return runCatching {
          val sourceDir = appContext.applicationInfo.sourceDir
          val appPackage = appContext.packageName
          var total = 0
          var inApp = 0

          DexFile(sourceDir).entries().asSequence().forEach { className ->
            total++
            if (className.startsWith(appPackage)) {
              inApp++
            }
          }

          EditorDexClassStat(totalClassCount = total, appPackageClassCount = inApp)
        }
        .getOrElse { EditorDexClassStat(totalClassCount = 0, appPackageClassCount = 0) }
  }

  private fun scanClassArtifacts(): EditorClassArtifactStat {
    val roots =
        listOfNotNull(
            appContext.filesDir,
            appContext.cacheDir,
            appContext.codeCacheDir,
            appContext.externalCacheDir,
            appContext.getExternalFilesDir(null),
        )

    var classCount = 0
    var clazzCount = 0
    var ktCount = 0

    roots.forEach { root ->
      root.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        when {
          file.name.endsWith(".class", ignoreCase = true) -> classCount++
          file.name.endsWith(".clazz", ignoreCase = true) -> clazzCount++
          file.name.endsWith(".kt", ignoreCase = true) -> ktCount++
        }
      }
    }

    return EditorClassArtifactStat(classCount, clazzCount, ktCount)
  }

  private fun readCpuStat(): CpuStat {
    val processTicks =
        runCatching {
              val processTokens = File("/proc/self/stat").readText().trim().split(" ")
              (processTokens.getOrNull(13)?.toLongOrNull() ?: 0L) +
                  (processTokens.getOrNull(14)?.toLongOrNull() ?: 0L)
            }
            .getOrElse { Process.getElapsedCpuTime() }

    val systemTicks =
        runCatching {
              val systemTokens =
                  File("/proc/stat").readLines().firstOrNull()?.trim()?.split(Regex("\\s+"))
                      ?: emptyList()
              systemTokens.drop(1).mapNotNull { it.toLongOrNull() }.sum()
            }
            .getOrElse { SystemClock.elapsedRealtime() }

    return CpuStat(processTicks = processTicks, systemTicks = max(1L, systemTicks))
  }

  private fun calcCpuUsage(previous: CpuStat, current: CpuStat): Double {
    val processDelta = current.processTicks - previous.processTicks
    val systemDelta = max(1L, current.systemTicks - previous.systemTicks)
    return (processDelta.toDouble() / systemDelta.toDouble()) * 100.0
  }

  private suspend fun readTermuxSubsystemStats(): List<EditorSubsystemStat> {
    val result =
        TermuxCommand.run(appContext) {
          label("APM Process Sample")
          executable("sh")
          args("-c", "ps -A -o PID,NAME,%CPU,RSS,ARGS")
        }
    if (!result.isSuccess || result.stdout.isBlank()) return emptyList()

    val buckets = linkedMapOf<String, MutableSubsystemAccumulator>()
    val keywords =
        linkedMapOf(
            "Termux Shell" to listOf("termux", "sh", "bash", "zsh"),
            "Gradle" to listOf("gradle", "gradlew", "daemon"),
            "Gradle Tooling" to listOf("tooling", "kotlin-daemon"),
            "JVM" to listOf("java", "openjdk", "dalvikvm"),
        )

    result.stdout
        .lineSequence()
        .drop(1)
        .forEach { rawLine ->
          val line = rawLine.trim()
          if (line.isBlank()) return@forEach
          val parts = line.split(Regex("\\s+"), limit = 5)
          if (parts.size < 5) return@forEach
          val lower = parts[4].lowercase()
          val cpu = parts[2].toDoubleOrNull() ?: 0.0
          val rssKb = parts[3].toDoubleOrNull() ?: 0.0
          keywords.forEach { (bucketName, matches) ->
            if (matches.any { lower.contains(it) }) {
              val acc = buckets.getOrPut(bucketName) { MutableSubsystemAccumulator() }
              acc.processCount += 1
              acc.totalCpuPercent += cpu
              acc.totalRssMb += rssKb / 1024.0
            }
          }
        }

    return buckets
        .map { (name, acc) ->
          EditorSubsystemStat(
              name = name,
              processCount = acc.processCount,
              totalCpuPercent = acc.totalCpuPercent,
              totalRssMb = acc.totalRssMb,
          )
        }
        .sortedByDescending { it.totalCpuPercent }
  }

  private fun buildHealthAlerts(
      cpuUsagePercent: Double,
      javaHeapUsedMb: Double,
      javaHeapMaxMb: Double,
      gcTimeMs: Long,
  ): List<String> {
    val alerts = mutableListOf<String>()
    if (cpuUsagePercent >= 85.0) alerts += "CPU sustained high load"
    if (javaHeapMaxMb > 0 && (javaHeapUsedMb / javaHeapMaxMb) >= 0.90) alerts += "OOM risk: Java heap above 90%"
    if (gcTimeMs >= 200L) alerts += "GC pause elevated"
    if (alerts.isEmpty()) alerts += "No immediate ANR/OOM signal"
    return alerts
  }

  private data class CpuStat(
      val processTicks: Long,
      val systemTicks: Long,
  )

  private class ClassActivityTracker(private val appPackageName: String) {
    private val stats = LinkedHashMap<String, MutableHotClassStat>()

    fun sample(cpuDeltaMs: Double, memDeltaMb: Double): List<EditorHotClassStat> {
      val classHits = collectAppClassHits()
      if (classHits.isEmpty()) {
        return stats.values
            .sortedByDescending { it.totalCpuMs }
            .take(MAX_HOT_CLASSES)
            .map { it.toSnapshot() }
      }

      val totalHits = classHits.values.sum().coerceAtLeast(1)
      classHits.forEach { (className, hits) ->
        val ratio = hits.toDouble() / totalHits.toDouble()
        val cpuShare = cpuDeltaMs * ratio
        val memShare = memDeltaMb * ratio
        val item =
            stats.getOrPut(className) {
              MutableHotClassStat(
                  className = className,
                  calls = 0,
                  totalCpuMs = 0.0,
                  totalMemMb = 0.0,
                  peakMemMb = 0.0,
              )
            }
        item.calls += hits
        item.totalCpuMs += cpuShare
        item.totalMemMb += memShare
        item.peakMemMb = max(item.peakMemMb, memShare)
      }

      return stats.values
          .sortedByDescending { it.totalCpuMs }
          .take(MAX_HOT_CLASSES)
          .map { it.toSnapshot() }
    }

    private fun collectAppClassHits(): Map<String, Int> {
      val hits = mutableMapOf<String, Int>()
      Thread.getAllStackTraces().values.forEach { stack ->
        val frame = stack.firstOrNull { it.className.startsWith(appPackageName) } ?: return@forEach
        hits[frame.className] = (hits[frame.className] ?: 0) + 1
      }
      return hits
    }
  }

  private data class MutableHotClassStat(
      val className: String,
      var calls: Int,
      var totalCpuMs: Double,
      var totalMemMb: Double,
      var peakMemMb: Double,
  ) {
    fun toSnapshot(): EditorHotClassStat {
      val avgCpuMs = if (calls > 0) totalCpuMs / calls.toDouble() else 0.0
      val avgMemMb = if (calls > 0) totalMemMb / calls.toDouble() else 0.0
      return EditorHotClassStat(
          className = className,
          calls = calls,
          totalCpuMs = totalCpuMs,
          avgCpuMs = avgCpuMs,
          totalMemMb = totalMemMb,
          avgMemMb = avgMemMb,
          peakMemMb = peakMemMb,
      )
    }
  }

  private companion object {
    private const val MAX_HOT_CLASSES = 30
  }

  private data class MutableSubsystemAccumulator(
      var processCount: Int = 0,
      var totalCpuPercent: Double = 0.0,
      var totalRssMb: Double = 0.0,
  )
}
