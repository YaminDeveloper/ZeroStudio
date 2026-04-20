/*
 * @author android_zero
 *
 * 功能：依赖更新检测与管理 UI 界面 (Jetpack Compose 驱动)
 * 修复内容：
 * 1. 彻底移除了对 ComposeOwnerHelper 的依赖，解决了 Unresolved reference 报错。
 * 2. 在 Fragment 中，利用 ComposeView 自动查找父级 Owner 的特性，无需手动设置。
 * 3. 在 PopupWindow 中，使用 [setParentCompositionContext] 替代手动设置 Owner。
 *    这是 Compose 官方推荐的跨窗口上下文传递方式，既解决了报错，又能完美继承主题和生命周期。
 */
package com.itsaky.androidide.repository.dependencies.analyzer.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.fragments.BaseFragment
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.repository.dependencies.analyzer.ProjectAnalyzer
import com.itsaky.androidide.repository.dependencies.analyzer.impl.GradleProjectAnalyzerImpl
import com.itsaky.androidide.repository.dependencies.analyzer.internal.DependencyUpdater
import com.itsaky.androidide.repository.dependencies.models.datas.*
import com.itsaky.androidide.repository.dependencies.models.enums.*
import com.itsaky.androidide.repository.dependencies.models.interfaces.*
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.launch

class DependencyUpdateFragment : BaseFragment() {

  private val analyzer: ProjectAnalyzer = GradleProjectAnalyzerImpl()

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return ComposeView(requireContext()).apply {
      setContent {
        MaterialTheme {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DependencyUpdateScreen(
                analyzer = analyzer,
                onFlashSuccess = { requireActivity().flashSuccess(it) },
                onFlashError = { requireActivity().flashError(it) },
            )
          }
        }
      }
    }
  }
}

/** 依赖更新界面的主屏幕组合函数。 */
@Composable
fun DependencyUpdateScreen(
    analyzer: ProjectAnalyzer,
    onFlashSuccess: (String) -> Unit,
    onFlashError: (String) -> Unit,
) {
  var reports by remember { mutableStateOf<List<UpdateReport>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  val coroutineScope = rememberCoroutineScope()

  fun refreshData() {
    isLoading = true
    coroutineScope.launch {
      val workspace = IProjectManager.getInstance().getWorkspace()
      val projectDir = workspace?.getProjectDir()
      if (projectDir != null) {
        val repos = analyzer.extractRepositories(projectDir)
        val deps = analyzer.extractDependencies(projectDir)
        reports = analyzer.checkUpdates(deps, repos)
      }
      isLoading = false
    }
  }

  LaunchedEffect(Unit) { refreshData() }

  if (isLoading) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Analyzing dependencies & checking updates...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  } else {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
      items(
          items = reports,
          key = { report ->
            val dep = report.dependency as? ScopedDependencyInfo
            "${report.dependency.gav}:${dep?.declaredFile?.absolutePath ?: "unknown"}:${dep?.statementTextRange?.startOffset ?: -1}"
          },
          contentType = { "dependency_item" },
      ) {
          report ->
        DependencyUpdateItem(
            report = report,
            onUpdateClicked = { selectedVersion ->
              if (selectedVersion == report.dependency.version) {
                onFlashSuccess("Already using ${report.dependency.artifactId}:$selectedVersion")
              } else {
                coroutineScope.launch {
                  val success = DependencyUpdater.update(report.dependency, selectedVersion)
                  if (success) {
                    onFlashSuccess("Updated ${report.dependency.artifactId} to $selectedVersion")
                    refreshData()
                  } else {
                    onFlashError(
                        "Failed to update ${report.dependency.artifactId}. No match found."
                    )
                  }
                }
              }
            },
        )
        Divider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
      }
    }
  }
}

/** 单项依赖视图 */
@Composable
fun DependencyUpdateItem(report: UpdateReport, onUpdateClicked: (String) -> Unit) {
  var selectedVersion by remember { mutableStateOf(report.dependency.version) }
  val currentView = LocalView.current

  // 这个 Context 包含了当前的 Lifecycle、SavedState 以及 Theme 信息。
  val compositionContext = rememberCompositionContext()
  val hasUpdate = report.latestVersion != report.dependency.version

  Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
          text = "${report.dependency.groupId}:${report.dependency.artifactId}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      val scoped = report.dependency as? ScopedDependencyInfo
      scoped?.let {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${it.configuration} · ${it.declaredFile.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text =
              if (hasUpdate) {
                "Current: ${report.dependency.version}  →  Latest: ${report.latestVersion}"
              } else {
                "Current: ${report.dependency.version}  ·  Latest: ${report.latestVersion}"
              },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedButton(
          onClick = {
            // 将 compositionContext 传递给 PopupWindow
            VersionSelectionPopupWindow(
                    context = currentView.context,
                    versions = report.availableVersions,
                    parentCompositionContext = compositionContext, // 传递上下文
                ) { version ->
                  selectedVersion = version
                }
                .showAtLocation(currentView, Gravity.CENTER, 0, 0)
          },
          modifier = Modifier.padding(end = 8.dp),
      ) {
        Text(text = selectedVersion)
      }

      Button(onClick = { onUpdateClicked(selectedVersion) }) { Text("Apply") }
    }
  }
}

/**
 * 使用 [setParentCompositionContext] 替代了直接设置 ViewTreeLifecycleOwner。 这是一个纯 Compose 的 API，不依赖 AndroidX
 * Lifecycle 的特定类，因此不会报 Unresolved reference。
 */
class VersionSelectionPopupWindow(
    context: Context,
    versions: List<String>,
    parentCompositionContext: CompositionContext,
    onVersionSelected: (String) -> Unit,
) : PopupWindow(context) {

  init {
    val composeView =
        ComposeView(context).apply {
          setParentCompositionContext(parentCompositionContext)

          setContent {
            MaterialTheme {
              Surface(
                  modifier = Modifier.fillMaxWidth(0.7f).heightIn(max = 400.dp),
                  shape = MaterialTheme.shapes.medium,
                  shadowElevation = 8.dp,
                  color = MaterialTheme.colorScheme.surface,
              ) {
                Column {
                  Text(
                      text = "Select Version",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(16.dp),
                  )
                  Divider()
                  LazyColumn {
                    items(versions.reversed()) { version ->
                      Text(
                          text = version,
                          modifier =
                              Modifier.fillMaxWidth()
                                  .clickable {
                                    onVersionSelected(version)
                                    dismiss()
                                  }
                                  .padding(horizontal = 16.dp, vertical = 12.dp),
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                }
              }
            }
          }
        }

    contentView = composeView
    width = ViewGroup.LayoutParams.WRAP_CONTENT
    height = ViewGroup.LayoutParams.WRAP_CONTENT
    isFocusable = true
    isOutsideTouchable = true
    elevation = 16f

    // 必须手动销毁 Composition，否则会内存泄漏
    setOnDismissListener { composeView.disposeComposition() }
  }
}
