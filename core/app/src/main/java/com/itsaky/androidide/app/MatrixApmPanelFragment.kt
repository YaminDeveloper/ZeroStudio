package com.itsaky.androidide.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.fragments.BaseFragment
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MatrixApmPanelFragment : BaseFragment() {

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
  ): View {
    val app = requireActivity().application
    val store = MatrixIssueStore(app)

    return ComposeView(requireContext()).apply {
      setContent {
        MaterialTheme {
          MatrixApmPanel(
              application = app,
              store = store,
              onExportSuccess = { requireActivity().flashSuccess(it) },
              onExportFailed = { requireActivity().flashError(it) }
          )
        }
      }
    }
  }
}

private enum class ApmFilter { ALL, MODULE, PLUGIN }

@Composable
private fun MatrixApmPanel(
    application: android.app.Application,
    store: MatrixIssueStore,
    onExportSuccess: (String) -> Unit,
    onExportFailed: (String) -> Unit,
) {
  var records by remember { mutableStateOf(store.queryRecent(limit = 400)) }
  var filter by remember { mutableStateOf(ApmFilter.ALL) }

  val scope = rememberCoroutineScope()

  fun refresh() {
    records = store.queryRecent(limit = 400)
  }

  fun exportCurrent(filtered: List<MatrixIssueRecord>) {
    scope.launch {
      runCatching {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target =
                File(application.getExternalFilesDir("exports"), "matrix-apm-$ts.ndjson")
            withContext(Dispatchers.IO) { store.export(filtered, target) }
          }
          .onSuccess { onExportSuccess("APM 导出成功: ${it.absolutePath}") }
          .onFailure { onExportFailed("APM 导出失败: ${it.message}") }
    }
  }

  val filtered =
      records.filter {
        when (filter) {
          ApmFilter.ALL -> true
          ApmFilter.MODULE -> it.plugin.startsWith("module:")
          ApmFilter.PLUGIN -> !it.plugin.startsWith("module:")
        }
      }

  val grouped = remember(filtered) { filtered.groupingBy { it.plugin }.eachCount().toList().sortedByDescending { it.second } }

  Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      FilterChip(selected = filter == ApmFilter.ALL, onClick = { filter = ApmFilter.ALL }, label = { Text("全部") })
      FilterChip(selected = filter == ApmFilter.MODULE, onClick = { filter = ApmFilter.MODULE }, label = { Text("模块") })
      FilterChip(selected = filter == ApmFilter.PLUGIN, onClick = { filter = ApmFilter.PLUGIN }, label = { Text("插件") })
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedButton(onClick = { refresh() }) { Text("刷新") }
      Button(onClick = { exportCurrent(filtered) }, enabled = filtered.isNotEmpty()) { Text("导出") }
    }

    Text(
        text = "时间线 (${filtered.size})",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )

    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
      items(filtered, key = { "${it.ts}-${it.plugin}-${it.content.hashCode()}" }) { rec ->
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Text(rec.plugin, style = MaterialTheme.typography.labelMedium)
            Text(rec.content, style = MaterialTheme.typography.bodySmall)
            Text("ts=${rec.ts}", style = MaterialTheme.typography.labelSmall)
          }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
      }
    }

    Text(
        text = "聚合统计",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
    )
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(0.8f)) {
      items(grouped, key = { it.first }) { (plugin, count) ->
        Text("$plugin : $count", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
