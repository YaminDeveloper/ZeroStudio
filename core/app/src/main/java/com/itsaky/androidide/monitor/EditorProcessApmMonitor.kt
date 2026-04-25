package com.itsaky.androidide.monitor

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
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
    val processUssMb: Double,
    val processVssMb: Double,
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
    val pssBreakdownStats: List<EditorPssBreakdownStat>,
    val cpuBreakdownStat: EditorCpuBreakdownStat,
    val ioStat: EditorIoStat,
    val topThreadStats: List<EditorThreadCpuStat>,
    val frameJankStat: EditorFrameJankStat,
    val deviceThermalStat: EditorDeviceThermalStat,
    val advancedHookStat: EditorAdvancedHookStat,
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

data class EditorPssBreakdownStat(
    val category: String,
    val pssMb: Double,
)

data class EditorCpuBreakdownStat(
    val userCpuMs: Long,
    val systemCpuMs: Long,
)

data class EditorIoStat(
    val readBytes: Long,
    val writeBytes: Long,
)

data class EditorThreadCpuStat(
    val tid: Int,
    val name: String,
    val cpuDeltaTicks: Long,
)

data class EditorFrameJankStat(
    val frameDropPercent: Double,
    val jankFrameCount: Long,
    val totalFrameCount: Long,
)

data class EditorDeviceThermalStat(
    val batteryTempCelsius: Double?,
    val level: String,
)

data class EditorAdvancedHookStat(
    val jvmtiReady: Boolean,
    val pltHookReady: Boolean,
    val inlineHookReady: Boolean,
    val mallocHookReady: Boolean,
    val memUnreachableReady: Boolean,
    val notes: List<String>,
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
    var previousJavaHeapUsedMb = readJavaHeapUsedMb()
    var previousNativeHeapMb = readNativeHeapMb()
    val dexStat = readDexClassStat()
    var classArtifactStat = scanClassArtifacts()
    val classActivityTracker =
        ClassActivityTracker(
            appPackageName = appContext.packageName,
            ignoredClassPrefixes =
                setOf(
                    "com.itsaky.androidide.monitor.",
                    "kotlinx.coroutines.",
                    "java.util.concurrent.",
                ),
        )
    val frameTracker = FrameJankTracker()
    frameTracker.start()
    var termuxSubsystemStats: List<EditorSubsystemStat> = emptyList()
    var pssBreakdownStats: List<EditorPssBreakdownStat> = emptyList()
    var previousThreadCpuStat = readThreadCpuTicks()
    var previousIoStat = readIoStat()
    var ticks = 0

    try {
      while (true) {
        val current = readCpuStat()
        val cpuUsage = calcCpuUsage(previous, current)
        previous = current
        val processPssMb = readProcessPssMb()
        val processUssMb = readProcessUssMb()
        val processVssMb = readProcessVssMb()
        val javaHeapUsedMb = readJavaHeapUsedMb()
        val nativeHeapMb = readNativeHeapMb()
        val processCpuMs = Process.getElapsedCpuTime()
        val cpuDeltaMs = max(0L, processCpuMs - previousProcessCpuMs).toDouble()
        val pssDeltaMb = (processPssMb - previousPssMb).coerceAtLeast(0.0)
        val javaHeapDeltaMb = (javaHeapUsedMb - previousJavaHeapUsedMb).coerceAtLeast(0.0)
        val nativeHeapDeltaMb = (nativeHeapMb - previousNativeHeapMb).coerceAtLeast(0.0)
        val attributedMemDeltaMb = max(pssDeltaMb, max(javaHeapDeltaMb, nativeHeapDeltaMb))
        previousProcessCpuMs = processCpuMs
        previousPssMb = processPssMb
        previousJavaHeapUsedMb = javaHeapUsedMb
        previousNativeHeapMb = nativeHeapMb

        if (ticks % 15 == 0) {
          classArtifactStat = scanClassArtifacts()
        }
        if (ticks % 3 == 0) {
          pssBreakdownStats = readPssBreakdownStats()
        }
        ticks++

        val hotClassStats =
            if (ticks % 2 == 0) {
              classActivityTracker.sample(cpuDeltaMs = cpuDeltaMs, memDeltaMb = attributedMemDeltaMb)
            } else {
              classActivityTracker.cachedTop()
            }
        if (ticks % 5 == 0) {
          termuxSubsystemStats = readTermuxSubsystemStats()
        }
        val javaHeapMaxMb = readJavaHeapMaxMb()
        val gcTimeMs = readGcTimeMs()
        val ioStat = readIoStat()
        val ioDeltaRead = (ioStat.readBytes - previousIoStat.readBytes).coerceAtLeast(0L)
        val ioDeltaWrite = (ioStat.writeBytes - previousIoStat.writeBytes).coerceAtLeast(0L)
        previousIoStat = ioStat

        val topThreadStats =
            if (ticks % 3 == 0) {
              val threadCpuStat = readThreadCpuTicks()
              val sampled = topThreadCpuDeltas(previousThreadCpuStat, threadCpuStat)
              previousThreadCpuStat = threadCpuStat
              sampled
            } else {
              emptyList()
            }

        val thermalStat = readBatteryThermalStat()
        val advancedHookStat = readAdvancedHookStat()
        val frameJankStat = frameTracker.snapshotAndReset()
        val cpuBreakdown = readCpuBreakdownStat()
        val alerts =
            buildHealthAlerts(
                cpuUsagePercent = cpuUsage,
                javaHeapUsedMb = javaHeapUsedMb,
                javaHeapMaxMb = javaHeapMaxMb,
                gcTimeMs = gcTimeMs,
                frameDropPercent = frameJankStat.frameDropPercent,
                batteryTempC = thermalStat.batteryTempCelsius,
            )

        emit(
            EditorProcessApmSnapshot(
                timestampMs = System.currentTimeMillis(),
                cpuUsagePercent = cpuUsage,
                processRssMb = readProcessRssMb(),
                processPssMb = processPssMb,
                processUssMb = processUssMb,
                processVssMb = processVssMb,
                javaHeapUsedMb = javaHeapUsedMb,
                javaHeapMaxMb = javaHeapMaxMb,
                nativeHeapMb = nativeHeapMb,
                threadCount = readThreadCount(),
                openFdCount = readOpenFdCount(),
                gcCount = readGcCount(),
                gcTimeMs = gcTimeMs,
                appUptimeMs = SystemClock.elapsedRealtime(),
                dexClassStat = dexStat,
                classArtifactStat = classArtifactStat,
                hotClassStats = hotClassStats,
                termuxSubsystemStats = termuxSubsystemStats,
                pssBreakdownStats = pssBreakdownStats,
                cpuBreakdownStat = cpuBreakdown,
                ioStat = EditorIoStat(readBytes = ioDeltaRead, writeBytes = ioDeltaWrite),
                topThreadStats = topThreadStats,
                frameJankStat = frameJankStat,
                deviceThermalStat = thermalStat,
                advancedHookStat = advancedHookStat,
                healthAlerts = alerts,
            )
        )
        delay(intervalMs)
      }
    } finally {
      frameTracker.stop()
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

  private fun readProcessUssMb(): Double {
    val privateKb =
        runCatching {
              File("/proc/self/smaps_rollup").useLines { lines ->
                lines
                    .mapNotNull { line ->
                      if (line.startsWith("Private_Clean:") || line.startsWith("Private_Dirty:")) {
                        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                      } else {
                        null
                      }
                    }
                    .sum()
              }
            }
            .getOrElse { 0L }
    return privateKb.toDouble() / 1024.0
  }

  private fun readProcessVssMb(): Double {
    val statm = File("/proc/self/statm")
    if (!statm.exists()) return 0.0
    val pages = statm.readText().trim().split(" ").getOrNull(0)?.toLongOrNull() ?: return 0.0
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

  private fun readPssBreakdownStats(): List<EditorPssBreakdownStat> {
    val categories =
        linkedMapOf(
            "Java Heap" to 0.0,
            "Native Heap" to 0.0,
            "Code" to 0.0,
            "Stack" to 0.0,
            "Graphics" to 0.0,
            "Private Other" to 0.0,
            "System" to 0.0,
            "Unknown" to 0.0,
        )
    runCatching {
          val memoryInfo = Debug.MemoryInfo()
          Debug.getMemoryInfo(memoryInfo)
          fun assign(key: String, kb: Int) {
            categories[key] = (kb.toDouble() / 1024.0).coerceAtLeast(0.0)
          }
          fun statKb(key: String): Int = memoryInfo.getMemoryStat(key)?.toIntOrNull() ?: 0
          assign("Java Heap", memoryInfo.dalvikPss)
          assign("Native Heap", memoryInfo.nativePss)
          assign("Code", statKb("summary.code"))
          assign("Stack", statKb("summary.stack"))
          assign("Graphics", statKb("summary.graphics"))
          assign("Private Other", statKb("summary.private-other"))
          assign("System", statKb("summary.system"))
          assign("Unknown", memoryInfo.otherPss)
        }
        .getOrDefault(Unit)
    return categories.map { (category, pssMb) -> EditorPssBreakdownStat(category = category, pssMb = pssMb) }
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

  private fun readCpuBreakdownStat(): EditorCpuBreakdownStat {
    val tokens = runCatching { File("/proc/self/stat").readText().trim().split(Regex("\\s+")) }.getOrElse { emptyList() }
    val clockTicksPerSecond = 100L
    val userTicks = tokens.getOrNull(13)?.toLongOrNull() ?: 0L
    val systemTicks = tokens.getOrNull(14)?.toLongOrNull() ?: 0L
    return EditorCpuBreakdownStat(
        userCpuMs = (userTicks * 1000L) / clockTicksPerSecond,
        systemCpuMs = (systemTicks * 1000L) / clockTicksPerSecond,
    )
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

  private fun readIoStat(): EditorIoStat {
    val pairs =
        runCatching { File("/proc/self/io").readLines() }
            .getOrElse { emptyList() }
            .mapNotNull { line ->
              val split = line.split(":")
              if (split.size == 2) split[0].trim() to split[1].trim().toLongOrNull() else null
            }
            .toMap()
    return EditorIoStat(
        readBytes = pairs["read_bytes"] ?: 0L,
        writeBytes = pairs["write_bytes"] ?: 0L,
    )
  }

  private fun readThreadCpuTicks(): Map<Int, ThreadCpuTick> {
    val taskDir = File("/proc/self/task")
    val dirs = taskDir.listFiles() ?: return emptyMap()
    val result = mutableMapOf<Int, ThreadCpuTick>()
    dirs.forEach { dir ->
      val tid = dir.name.toIntOrNull() ?: return@forEach
      val statText = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
      val nameStart = statText.indexOf('(')
      val nameEnd = statText.lastIndexOf(')')
      if (nameStart < 0 || nameEnd <= nameStart) return@forEach
      val threadName = statText.substring(nameStart + 1, nameEnd)
      val tokens = statText.substring(nameEnd + 2).split(" ")
      val utime = tokens.getOrNull(11)?.toLongOrNull() ?: 0L
      val stime = tokens.getOrNull(12)?.toLongOrNull() ?: 0L
      result[tid] = ThreadCpuTick(name = threadName, totalTicks = utime + stime)
    }
    return result
  }

  private fun readThreadCount(): Int = File("/proc/self/task").list()?.size ?: 0

  private fun topThreadCpuDeltas(
      previous: Map<Int, ThreadCpuTick>,
      current: Map<Int, ThreadCpuTick>,
  ): List<EditorThreadCpuStat> {
    return current.mapNotNull { (tid, currentTick) ->
      val previousTick = previous[tid] ?: return@mapNotNull null
      val delta = (currentTick.totalTicks - previousTick.totalTicks).coerceAtLeast(0L)
      if (delta == 0L) return@mapNotNull null
      EditorThreadCpuStat(tid = tid, name = currentTick.name, cpuDeltaTicks = delta)
    }.sortedByDescending { it.cpuDeltaTicks }.take(6)
  }

  private fun readBatteryThermalStat(): EditorDeviceThermalStat {
    val intent =
        appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
    val raw = intent?.getIntExtra("temperature", -1) ?: -1
    val celsius = if (raw > 0) raw / 10.0 else null
    val level =
        when {
          celsius == null -> "unknown"
          celsius >= 42.0 -> "danger"
          celsius >= 38.0 -> "warning"
          else -> "safe"
        }
    return EditorDeviceThermalStat(
        batteryTempCelsius = celsius,
        level = level,
    )
  }

  private fun readAdvancedHookStat(): EditorAdvancedHookStat {
    val notes = mutableListOf<String>()
    val jvmtiReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    if (jvmtiReady) {
      notes += "JVMTI dynamic agent is supported on Android 8.0+."
    } else {
      notes += "JVMTI requires Android 8.0+."
    }

    val nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir ?: "")
    val pltHookReady = nativeLibDir.listFiles()?.any { it.name.contains("plthook", ignoreCase = true) } == true
    val inlineHookReady = nativeLibDir.listFiles()?.any { it.name.contains("shadowhook", ignoreCase = true) || it.name.contains("inlinehook", ignoreCase = true) } == true
    val mallocHookReady = (System.getenv("LIBC_HOOKS_ENABLE") == "1") || hasAnyFile("/data/local/tmp/libc_malloc_hooks.so")
    val memUnreachableReady = hasAnyFile("/system/lib64/libmemunreachable.so", "/system/lib/libmemunreachable.so")

    if (!pltHookReady) notes += "PLT Hook bridge library not detected in app native libs."
    if (!inlineHookReady) notes += "Inline Hook bridge library not detected in app native libs."
    if (!mallocHookReady) notes += "jemalloc/scudo malloc hook bridge not enabled."
    if (!memUnreachableReady) notes += "libmemunreachable is unavailable on this device image."

    val externalProbeScript = File(appContext.filesDir, "apm-probes/collect.sh")
    if (externalProbeScript.exists()) {
      notes += "External advanced probe script detected: ${externalProbeScript.absolutePath}."
    } else {
      notes += "Optional external probes can be mounted at files/apm-probes/collect.sh with no app source patching."
    }

    return EditorAdvancedHookStat(
        jvmtiReady = jvmtiReady,
        pltHookReady = pltHookReady,
        inlineHookReady = inlineHookReady,
        mallocHookReady = mallocHookReady,
        memUnreachableReady = memUnreachableReady,
        notes = notes,
    )
  }

  private fun hasAnyFile(vararg paths: String): Boolean = paths.any { File(it).exists() }

  private fun buildHealthAlerts(
      cpuUsagePercent: Double,
      javaHeapUsedMb: Double,
      javaHeapMaxMb: Double,
      gcTimeMs: Long,
      frameDropPercent: Double,
      batteryTempC: Double?,
  ): List<String> {
    val alerts = mutableListOf<String>()
    if (cpuUsagePercent >= 85.0) alerts += "CPU sustained high load"
    if (javaHeapMaxMb > 0 && (javaHeapUsedMb / javaHeapMaxMb) >= 0.90) alerts += "OOM risk: Java heap above 90%"
    if (gcTimeMs >= 200L) alerts += "GC pause elevated"
    if (frameDropPercent >= 20.0) alerts += "UI jank risk: dropped-frame ratio elevated"
    if ((batteryTempC ?: 0.0) >= 42.0) alerts += "Device thermal danger: battery temperature too high"
    if (alerts.isEmpty()) alerts += "No immediate ANR/OOM signal"
    return alerts
  }

  private data class CpuStat(
      val processTicks: Long,
      val systemTicks: Long,
  )

  private class ClassActivityTracker(
      private val appPackageName: String,
      private val ignoredClassPrefixes: Set<String>,
  ) {
    private val stats = LinkedHashMap<String, MutableHotClassStat>()
    private val lastHitSnapshot = mutableMapOf<String, Double>()
    private var sampleSeq = 0L

    fun sample(cpuDeltaMs: Double, memDeltaMb: Double): List<EditorHotClassStat> {
      sampleSeq++
      if (cpuDeltaMs <= 0.1 && memDeltaMb <= 0.05) {
        return cachedTop()
      }
      val classHits = collectAppClassHitsWeighted()
      if (classHits.isEmpty()) {
        return cachedTop()
      }

      val totalHits = classHits.values.sum().takeIf { it > 0.0 } ?: 1.0
      classHits.forEach { (className, hits) ->
        val ratio = hits / totalHits
        val previousWeight = lastHitSnapshot[className] ?: 0.0
        val smoothRatio = (ratio * 0.7) + (previousWeight * 0.3)
        lastHitSnapshot[className] = smoothRatio
        val cpuShare = (cpuDeltaMs * ratio).coerceAtMost(cpuDeltaMs * 0.35)
        val memShare = (memDeltaMb * smoothRatio).coerceAtMost(memDeltaMb * 0.40)
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
        item.calls += max(1, (hits * 10.0).toInt())
        item.totalCpuMs += cpuShare
        item.totalMemMb += memShare
        item.peakMemMb = max(item.peakMemMb, memShare)
        item.lastSeenSeq = sampleSeq
        item.hitWeightEma = (item.hitWeightEma * 0.6) + (smoothRatio * 0.4)
      }

      stats.values.forEach { item ->
        val inactive = sampleSeq - item.lastSeenSeq
        if (inactive > 10) {
          item.totalCpuMs *= 0.92
          item.totalMemMb *= 0.90
        }
      }

      return cachedTop()
    }

    fun cachedTop(): List<EditorHotClassStat> {
      return stats.values
          .filter { sampleSeq - it.lastSeenSeq <= 45L }
          .sortedByDescending { (it.totalCpuMs * 0.75) + (it.hitWeightEma * 100.0) }
          .take(MAX_HOT_CLASSES)
          .map { it.toSnapshot() }
    }

    private fun collectAppClassHitsWeighted(): Map<String, Double> {
      val hits = mutableMapOf<String, Double>()
      var threadBudget = 40
      Thread.getAllStackTraces().values.forEach { stack ->
        if (threadBudget-- <= 0) return@forEach
        stack.take(4).forEachIndexed { index, frame ->
          if (!frame.className.startsWith(appPackageName)) return@forEachIndexed
          if (ignoredClassPrefixes.any { frame.className.startsWith(it) }) return@forEachIndexed
          val depthWeight = 1.0 / (index + 1).toDouble()
          hits[frame.className] = (hits[frame.className] ?: 0.0) + depthWeight
        }
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
      var lastSeenSeq: Long = 0L,
      var hitWeightEma: Double = 0.0,
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

  private data class ThreadCpuTick(
      val name: String,
      val totalTicks: Long,
  )

  private class FrameJankTracker {
    @Volatile private var running = false
    @Volatile private var totalFrames = 0L
    @Volatile private var jankFrames = 0L
    private var lastFrameNs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callback =
        object : Choreographer.FrameCallback {
          override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNs > 0L) {
              val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000.0
              totalFrames++
              if (deltaMs > 16.6) {
                jankFrames++
              }
            }
            lastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
          }
        }

    fun start() {
      running = true
      mainHandler.post {
        runCatching {
          Choreographer.getInstance().postFrameCallback(callback)
        }
      }
    }

    fun stop() {
      running = false
      mainHandler.post {
        runCatching {
          Choreographer.getInstance().removeFrameCallback(callback)
        }
      }
    }

    fun snapshotAndReset(): EditorFrameJankStat {
      val total = totalFrames
      val jank = jankFrames
      totalFrames = 0L
      jankFrames = 0L
      val dropPercent = if (total <= 0L) 0.0 else (jank.toDouble() / total.toDouble()) * 100.0
      return EditorFrameJankStat(
          frameDropPercent = dropPercent,
          jankFrameCount = jank,
          totalFrameCount = total,
      )
    }
  }
}
