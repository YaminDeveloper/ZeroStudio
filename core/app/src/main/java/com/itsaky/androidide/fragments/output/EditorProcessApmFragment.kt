package com.itsaky.androidide.fragments.output

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.monitor.EditorHotClassStat
import com.itsaky.androidide.monitor.EditorProcessApmMonitor
import com.itsaky.androidide.monitor.EditorProcessApmSnapshot
import com.itsaky.androidide.monitor.EditorSubsystemStat
import com.itsaky.androidide.utils.executioncommand.TermuxCommand
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Editor bottom-sheet APM dashboard fragment rendered with Jetpack Compose. */
class EditorProcessApmFragment : Fragment() {

  private var monitorJob: Job? = null
  private val snapshotState = mutableStateOf<EditorProcessApmSnapshot?>(null)

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return ComposeView(requireContext()).apply {
      setContent {
        EditorApmMonitorScreen(
            snapshot = snapshotState.value,
            onCleanProcesses = ::runCleanupViaTermux,
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    val monitor = EditorProcessApmMonitor(requireContext().applicationContext)
    monitorJob?.cancel()
    monitorJob =
        viewLifecycleOwner.lifecycleScope.launch {
          monitor.stream(intervalMs = 1000L).collect { snapshot -> snapshotState.value = snapshot }
        }
  }

  override fun onStop() {
    monitorJob?.cancel()
    super.onStop()
  }

  private fun runCleanupViaTermux() {
    viewLifecycleOwner.lifecycleScope.launch {
      TermuxCommand.run(requireContext().applicationContext) {
        label("APM Cleanup Gradle/JVM")
        executable("sh")
        args(
            "-c",
            "gradle --stop 2>/dev/null; " +
                "pkill -f 'gradle.*daemon' 2>/dev/null; " +
                "pkill -f 'java.*gradle' 2>/dev/null; " +
                "pkill -f 'gradle' 2>/dev/null; " +
                "echo 'Gradle and Java cleanup done.'",
        )
      }
    }
  }
}

@Composable
private fun EditorApmMonitorScreen(
    snapshot: EditorProcessApmSnapshot?,
    onCleanProcesses: () -> Unit,
) {
  val cpuHistory = remember { mutableStateListOf<Float>() }
  val pssHistory = remember { mutableStateListOf<Float>() }
  var menuExpanded by remember { mutableStateOf(false) }

  LaunchedEffect(snapshot?.timestampMs) {
    snapshot?.let {
      cpuHistory.add(it.cpuUsagePercent.toFloat())
      pssHistory.add(it.processPssMb.toFloat())
      if (cpuHistory.size > MAX_HISTORY_POINTS) cpuHistory.removeAt(0)
      if (pssHistory.size > MAX_HISTORY_POINTS) pssHistory.removeAt(0)
    }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("APM 实时监控") },
            actions = {
              IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
              }
              DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("清理 Gradle/Java 进程") },
                    onClick = {
                      menuExpanded = false
                      onCleanProcesses()
                    },
                )
              }
            },
        )
      },
  ) { contentPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text(
            text = "采样: 1s（类文件扫描: 15s）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      item {
        MetricChartCard(
            title = "CPU 使用率(%)",
            value = format(snapshot?.cpuUsagePercent, "%"),
            values = cpuHistory,
            lineColor = Color(0xFF7E57C2),
        )
      }
      item {
        MetricChartCard(
            title = "进程 PSS(MB)",
            value = format(snapshot?.processPssMb, "MB"),
            values = pssHistory,
            lineColor = Color(0xFF26A69A),
        )
      }
      item {
        AdvancedOverviewCard(snapshot = snapshot)
      }
      item {
        HealthAlertsCard(alerts = snapshot?.healthAlerts.orEmpty())
      }
      item {
        MetricGrid(
            listOf(
                "RSS" to format(snapshot?.processRssMb, "MB"),
                "Java Heap" to
                    "${format(snapshot?.javaHeapUsedMb, "MB")} / ${format(snapshot?.javaHeapMaxMb, "MB")}",
                "Native Heap" to format(snapshot?.nativeHeapMb, "MB"),
                "线程数" to "${snapshot?.threadCount ?: 0}",
                "打开FD" to "${snapshot?.openFdCount ?: 0}",
                "GC次数" to "${snapshot?.gcCount ?: 0}",
                "GC耗时" to "${snapshot?.gcTimeMs ?: 0} ms",
                "Uptime" to "${(snapshot?.appUptimeMs ?: 0L) / 1000}s",
            ))
      }
      item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
          Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("类加载与产物统计", fontWeight = FontWeight.SemiBold)
            Text("Dex总类数: ${snapshot?.dexClassStat?.totalClassCount ?: 0}")
            Text("App包类数: ${snapshot?.dexClassStat?.appPackageClassCount ?: 0}")
            Text("*.class: ${snapshot?.classArtifactStat?.classFileCount ?: 0}")
            Text("*.clazz: ${snapshot?.classArtifactStat?.clazzFileCount ?: 0}")
            Text("*.kt: ${snapshot?.classArtifactStat?.kotlinFileCount ?: 0}")
          }
        }
      }
      item {
        HotClassActivityCard(classStats = snapshot?.hotClassStats.orEmpty())
      }
      item {
        TermuxSubsystemCard(stats = snapshot?.termuxSubsystemStats.orEmpty())
      }
    }
  }
}

@Composable
private fun AdvancedOverviewCard(snapshot: EditorProcessApmSnapshot?) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("高级监控能力", fontWeight = FontWeight.SemiBold)
      Text("CPU/APM: 进程CPU、热点类活动估算")
      Text("内存: RSS/PSS/Java/Native + 热点类内存增量估算")
      Text("GC: 次数与耗时")
      val pressureLevel = when {
        (snapshot?.cpuUsagePercent ?: 0.0) >= 80.0 -> "高"
        (snapshot?.cpuUsagePercent ?: 0.0) >= 40.0 -> "中"
        else -> "低"
      }
      Text("性能压力等级: $pressureLevel")
      Text(
          "说明: 热点类数据基于线程栈采样 + 增量分摊估算，用于定位可疑高消耗模块，不是精确 profiler 结果。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.sp,
      )
    }
  }
}

@Composable
private fun HealthAlertsCard(alerts: List<String>) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("ANR/OOM 预警", fontWeight = FontWeight.SemiBold)
      alerts.forEach { alert ->
        Text("• $alert", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items.chunked(2).forEach { rowItems ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowItems.forEach { pair ->
          Card(
              modifier = Modifier.weight(1f),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Text(pair.first, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(modifier = Modifier.height(4.dp))
              Text(pair.second, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MetricChartCard(
    title: String,
    value: String,
    values: List<Float>,
    lineColor: Color,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Spacer(modifier = Modifier.height(8.dp))
      Sparkline(values = values, lineColor = lineColor)
    }
  }
}

@Composable
private fun Sparkline(values: List<Float>, lineColor: Color) {
  Column(modifier = Modifier.fillMaxWidth()) {
    val min = values.minOrNull() ?: 0f
    val max = values.maxOrNull() ?: 0f
    val current = values.lastOrNull() ?: 0f
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Min ${formatFloat(min)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("Now ${formatFloat(current)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("Max ${formatFloat(max)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(modifier = Modifier.height(6.dp))
    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        if (values.size < 2) return@Canvas
        val maxValue = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val minValue = values.minOrNull() ?: 0f
        val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f

        val stepX = size.width / (values.size - 1)
        val linePath = Path()
        val fillPath = Path()

        values.forEachIndexed { index, value ->
          val normalized = (value - minValue) / range
          val x = stepX * index
          val y = size.height - (normalized * size.height)
          if (index == 0) {
            linePath.moveTo(x, y)
            fillPath.moveTo(x, size.height)
            fillPath.lineTo(x, y)
          } else {
            linePath.lineTo(x, y)
            fillPath.lineTo(x, y)
          }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        val gridStep = size.height / 4f
        repeat(5) { idx ->
          val y = idx * gridStep
          drawLine(
              color = Color.Gray.copy(alpha = 0.22f),
              start = Offset(0f, y),
              end = Offset(size.width, y),
              strokeWidth = 1.dp.toPx(),
          )
        }

        drawPath(path = fillPath, color = lineColor.copy(alpha = 0.14f), style = Fill)
        drawPath(path = linePath, color = lineColor, style = Stroke(width = 2.dp.toPx()))
      }
    }
  }
}

@Composable
private fun TermuxSubsystemCard(stats: List<EditorSubsystemStat>) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Termux/Gradle/JVM 子系统监控", fontWeight = FontWeight.SemiBold)
      if (stats.isEmpty()) {
        Text("暂无终端子系统采样数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return@Column
      }
      stats.forEach { stat ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(stat.name, fontWeight = FontWeight.Medium)
          Text(
              "proc=${stat.processCount} totalCPU=${formatFloat(stat.totalCpuPercent)}% totalMem=${formatFloat(stat.totalRssMb)}MB",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun HotClassActivityCard(classStats: List<EditorHotClassStat>) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("热点类活动追踪（Top 15）", fontWeight = FontWeight.SemiBold)
      if (classStats.isEmpty()) {
        Text("暂无数据，等待采样…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return@Column
      }

      classStats.take(15).forEach { stat ->
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(stat.className, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
              "calls=${stat.calls} totalCPU=${formatFloat(stat.totalCpuMs)} ms avgCPU=${formatFloat(stat.avgCpuMs)} ms",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
              "totalMem=${formatFloat(stat.totalMemMb)} MB avgMem=${formatFloat(stat.avgMemMb)} MB peakA=${formatFloat(stat.peakMemMb)} MB",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun format(value: Double?, suffix: String): String {
  if (value == null) return "--"
  return String.format(Locale.US, "%.2f %s", value, suffix)
}

private fun formatFloat(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)

private const val MAX_HISTORY_POINTS = 180
