/*
 * @author android_zero
 */
package com.itsaky.androidide.repository.dependencies.analyzer.impl

import com.itsaky.androidide.repository.dependencies.analyzer.ProjectAnalyzer
import com.itsaky.androidide.repository.dependencies.analyzer.internal.*
import com.itsaky.androidide.repository.dependencies.analyzer.network.MavenMetadataFetcher
import com.itsaky.androidide.repository.dependencies.models.datas.*
import com.itsaky.androidide.repository.dependencies.models.enums.RepositoryType
import com.itsaky.androidide.repository.dependencies.models.interfaces.DependencyInfo
import com.itsaky.androidide.repository.dependencies.models.interfaces.RepositoryInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/** <h1>Gradle 项目依赖与仓库分析器 - 最终实现</h1> */
class GradleProjectAnalyzerImpl : ProjectAnalyzer {

  private val linker = DependencyLinker()
  private val tomlParser = TomlCatalogParser()
  private val scanner = WorkspaceDependencyScanner(tomlParser = tomlParser, linker = linker)

  override suspend fun extractRepositories(projectDir: File): List<ScopedRepositoryInfo> =
      withContext(Dispatchers.IO) {
        val scanResult = scanner.scan(projectDir)
        val repos = scanResult.repositories.toMutableList()

        repos.add(
            ScopedRepositoryInfo(
                "google",
                "https://dl.google.com/dl/android/maven2/",
                RepositoryType.GOOGLE,
                File(projectDir, "build.gradle"),
            )
        )
        repos.add(
            ScopedRepositoryInfo(
                "gradle",
                "https://plugins.gradle.org/m2",
                RepositoryType.MAVEN_CENTRAL,
                File(projectDir, "build.gradle"),
            )
        )
        repos.add(
            ScopedRepositoryInfo(
                "mavenCentral",
                "https://repo1.maven.org/maven2/",
                RepositoryType.MAVEN_CENTRAL,
                File(projectDir, "build.gradle"),
            )
        )

        repos.distinctBy { it.url }
      }

  override suspend fun extractDependencies(projectDir: File): List<DependencyInfo> =
      withContext(Dispatchers.IO) {
        val scanResult = scanner.scan(projectDir)
        scanResult.dependencies
      }

  override suspend fun checkUpdates(
      dependencies: List<DependencyInfo>,
      repositories: List<RepositoryInfo>,
  ): List<UpdateReport> =
      withContext(Dispatchers.IO) {

        // 过滤出 ScopedDependencyInfo 类型
        val distinctDeps =
            dependencies.filterIsInstance<ScopedDependencyInfo>().distinctBy { it.gav }

        val tasks = distinctDeps.map { dep ->
          async {
            var bestLatest: String? = null
            val allVersions = mutableListOf<String>()
            val gavPath = "${dep.groupId.replace('.', '/')}/${dep.artifactId}"

            for (repo in repositories) {
              val metadata = MavenMetadataFetcher.fetchMetadata(gavPath, repo.url)
              if (metadata != null) {
                val stableVersions = metadata.versions.filter(::isStableVersion)
                allVersions.addAll(stableVersions)

                val remoteLatest =
                    stableVersions.maxWithOrNull(SemanticVersionComparator)
                        ?: metadata.bestLatest?.takeIf(::isStableVersion)
                        ?: metadata.release?.takeIf(::isStableVersion)
                        ?: metadata.latest?.takeIf(::isStableVersion)

                if (remoteLatest != null && isNewerSemanticVersion(remoteLatest, dep.version)) {
                  bestLatest =
                      listOfNotNull(bestLatest, remoteLatest)
                          .maxWithOrNull(SemanticVersionComparator)
                }
              }
            }

            val sortedVersions =
                (allVersions + dep.version).distinct().sortedWith(SemanticVersionComparator).reversed()
            val chosenLatest =
                bestLatest
                    ?.takeIf { isNewerSemanticVersion(it, dep.version) }
                    ?: sortedVersions.firstOrNull()
                    ?: dep.version

            UpdateReport(
                dependency = dep,
                latestVersion = chosenLatest,
                availableVersions = sortedVersions.ifEmpty { listOf(dep.version) },
            )
          }
        }

        return@withContext tasks.awaitAll()
      }

  private object SemanticVersionComparator : Comparator<String> {
    override fun compare(v1: String, v2: String): Int {
      val parts1 = v1.split(Regex("[.-]")).mapNotNull { it.toIntOrNull() }
      val parts2 = v2.split(Regex("[.-]")).mapNotNull { it.toIntOrNull() }
      val length = maxOf(parts1.size, parts2.size)
      for (i in 0 until length) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1 - p2
      }
      return 0
    }
  }

  private fun isStableVersion(version: String): Boolean {
    val value = version.lowercase()
    return !value.contains("alpha") &&
        !value.contains("beta") &&
        !value.contains("rc") &&
        !value.contains("snapshot")
  }

  private fun isNewerSemanticVersion(latest: String, current: String): Boolean {
    if (latest == current) return false
    return SemanticVersionComparator.compare(latest, current) > 0
  }
}
