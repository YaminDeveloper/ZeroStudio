package com.itsaky.androidide.repository.dependencies.analyzer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.fragments.BaseFragment
// import com.itsaky.androidide.app.MatrixApmTracker
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.repository.dependencies.analyzer.ProjectAnalyzer
import com.itsaky.androidide.repository.dependencies.analyzer.impl.GradleProjectAnalyzerImpl
import com.itsaky.androidide.repository.dependencies.analyzer.internal.DependencyUpdater
import com.itsaky.androidide.repository.dependencies.models.datas.ScopedDependencyInfo
import com.itsaky.androidide.repository.dependencies.models.datas.UpdateReport
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class DependencyUpdateFragment : BaseFragment() {

  private val analyzer: ProjectAnalyzer = GradleProjectAnalyzerImpl()

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
  ): View {
    return ComposeView(requireContext()).apply {
      setContent {
        MaterialTheme {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DependencyUpdateScreen(
                analyzer = analyzer,
                onFlashSuccess = { requireActivity().flashSuccess(it) },
                onFlashError = { requireActivity().flashError(it) }
            )
          }
        }
      }
    }
  }
}

private enum class DependencyFilter {
  ONLY_OUTDATED,
  ALL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DependencyUpdateScreen(
    analyzer: ProjectAnalyzer,
    onFlashSuccess: (String) -> Unit,
    onFlashError: (String) -> Unit
) {
  var reports by remember { mutableStateOf<List<UpdateReport>>(emptyList()) }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var warningMessage by remember { mutableStateOf<String?>(null) }
  var filter by rememberSaveable { mutableStateOf(DependencyFilter.ALL) }
  var autoRetryAttempts by remember { mutableStateOf(0) }

  val scope = rememberCoroutineScope()

  fun refresh() {
    val scanStart = System.currentTimeMillis()
    scope.launch {
      isLoading = true
      errorMessage = null
      warningMessage = null
      try {
        val workspace = IProjectManager.getInstance().getWorkspace()
        val projectDir = workspace?.getProjectDir()
        if (projectDir == null) {
          reports = emptyList()
          errorMessage = "No opened workspace. Please open a project first."
          return@launch
        }

        val scopedDeps =
            analyzer.extractDependencies(projectDir).filterIsInstance<ScopedDependencyInfo>().distinctBy { it.gav }

        val fallback = scopedDeps.map { dep ->
          UpdateReport(
              dependency = dep,
              latestVersion = dep.version,
              availableVersions = listOf(dep.version)
          )
        }
        reports = fallback

        if (scopedDeps.isNotEmpty()) {
          try {
            val repos = analyzer.extractRepositories(projectDir)
            val updateMap = analyzer.checkUpdates(scopedDeps, repos).associateBy { it.dependency.gav }
            reports = fallback.map { base -> updateMap[base.dependency.gav] ?: base }
          } catch (e: Exception) {
            warningMessage =
                "Loaded project dependencies, but metadata check failed: ${e.message ?: "unknown error"}"
          }
        } else {
          warningMessage =
              "No dependencies parsed from Gradle DSL/TOML. Check settings.gradle and libs.versions.toml mapping."
        }

        reports = reports.sortedBy { it.dependency.gav }
        if (reports.isNotEmpty()) {
          autoRetryAttempts = 0
        }
      } catch (e: Exception) {
        reports = emptyList()
        errorMessage = e.message ?: "Unknown error while loading dependencies."
        onFlashError("Dependency scan failed: ${errorMessage}")
      } finally {
        // MatrixApmTracker.reportModuleEvent(
        //     module = "dependency-scan",
        //     event = if (errorMessage == null) "refresh_ok" else "refresh_error",
        //     costMs = System.currentTimeMillis() - scanStart,
        //     extra = "count=${reports.size}"
        // )
        isLoading = false
      }
    }
  }

  LaunchedEffect(Unit) { refresh() }
  LaunchedEffect(isLoading, errorMessage, reports.size, autoRetryAttempts) {
    if (!isLoading && errorMessage == null && reports.isEmpty() && autoRetryAttempts < 5) {
      autoRetryAttempts += 1
      delay(1200)
      refresh()
    }
  }

  val visibleReports = when (filter) {
    DependencyFilter.ONLY_OUTDATED -> reports.filter { it.latestVersion != it.dependency.version }
    DependencyFilter.ALL -> reports
  }

  Scaffold { innerPadding ->
    when {
      isLoading -> LoadingState(innerPadding)
      errorMessage != null -> ErrorState(innerPadding, errorMessage ?: "Unknown error", onRetry = { refresh() })
      else ->
          DependencyListState(
              innerPadding = innerPadding,
              reports = visibleReports,
              totalCount = reports.size,
              filter = filter,
              isRefreshing = isLoading,
              warningMessage = warningMessage,
              onFilterChange = { filter = it },
              onRefresh = {
                autoRetryAttempts = 0
                refresh()
              },
              onApplyClicked = { report, selectedVersion ->
                if (selectedVersion == report.dependency.version) {
                  onFlashSuccess("Already using ${report.dependency.artifactId}:$selectedVersion")
                  return@DependencyListState
                }
                scope.launch {
                  val success = DependencyUpdater.update(report.dependency, selectedVersion)
                  if (success) {
                    onFlashSuccess("Updated ${report.dependency.artifactId} to $selectedVersion")
                    refresh()
                  } else {
                    onFlashError("Failed to update ${report.dependency.artifactId}")
                  }
                }
              }
          )
    }
  }
}

@Composable
private fun LoadingState(innerPadding: PaddingValues) {
  Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator()
      Spacer(modifier = Modifier.height(12.dp))
      Text("Scanning dependencies...")
    }
  }
}

@Composable
private fun ErrorState(innerPadding: PaddingValues, message: String, onRetry: () -> Unit) {
  Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp),
      contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
          text = "Failed to load dependencies",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
      Spacer(modifier = Modifier.height(16.dp))
      Button(onClick = onRetry) { Text("Retry") }
    }
  }
}

@Composable
private fun DependencyListState(
    innerPadding: PaddingValues,
    reports: List<UpdateReport>,
    totalCount: Int,
    filter: DependencyFilter,
    isRefreshing: Boolean,
    warningMessage: String?,
    onFilterChange: (DependencyFilter) -> Unit,
    onRefresh: () -> Unit,
    onApplyClicked: (UpdateReport, String) -> Unit
) {
  val listState = rememberLazyListState()

  Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
    Column(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      FilterChip(
          selected = filter == DependencyFilter.ONLY_OUTDATED,
          onClick = { onFilterChange(DependencyFilter.ONLY_OUTDATED) },
          label = { Text("Outdated only") }
      )
      FilterChip(
          selected = filter == DependencyFilter.ALL,
          onClick = { onFilterChange(DependencyFilter.ALL) },
          label = { Text("All ($totalCount)") }
      )
      OutlinedButton(onClick = onRefresh, enabled = !isRefreshing) { Text("Refresh") }
    }

    if (!warningMessage.isNullOrBlank()) {
      Text(
          text = warningMessage,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
      )
    } else {
      Text(
          text = "Detected dependencies: $totalCount",
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

      if (reports.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
              text = "No dependencies to show for current filter.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
          items(items = reports, key = { it.dependency.gav }) { report ->
            DependencyUpdateItem(report = report, onApplyClicked = onApplyClicked)
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun DependencyUpdateItem(report: UpdateReport, onApplyClicked: (UpdateReport, String) -> Unit) {
  var selectedVersion by remember(report.dependency.gav) { mutableStateOf(report.dependency.version) }
  var menuExpanded by remember(report.dependency.gav) { mutableStateOf(false) }
  val versions = remember(report.availableVersions) {
    (report.availableVersions + report.dependency.version).distinct().ifEmpty { listOf(report.dependency.version) }
  }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
      Text(
          text = "${report.dependency.groupId}:${report.dependency.artifactId}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = "Current: ${report.dependency.version}  Latest: ${report.latestVersion}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(12.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
          OutlinedButton(onClick = { menuExpanded = true }) { Text(selectedVersion) }
          DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            versions.forEach { version ->
              val isCurrentVersion = version == report.dependency.version
              DropdownMenuItem(
                  text = {
                    Text(
                        text = version,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .then(
                                    if (isCurrentVersion) {
                                      Modifier
                                          .padding(0.dp)
                                          .background(MaterialTheme.colorScheme.secondaryContainer)
                                    } else {
                                      Modifier
                                    }
                                ),
                        fontWeight = if (version == selectedVersion) FontWeight.SemiBold else FontWeight.Normal
                    )
                  },
                  onClick = {
                    selectedVersion = version
                    menuExpanded = false
                    onApplyClicked(report, selectedVersion)
                  })
            }
          }
        }
      }
    }
  }
}
