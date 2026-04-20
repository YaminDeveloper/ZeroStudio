package com.itsaky.androidide.repository.dependencies.analyzer.internal

import com.itsaky.androidide.repository.dependencies.models.datas.ScopedDependencyInfo
import com.itsaky.androidide.repository.dependencies.models.datas.VersionCatalog
import com.itsaky.androidide.repository.dependencies.models.enums.DeclarationType
import java.io.File

/**
 * 主动扫描工作区内的所有 Gradle Groovy/Kotlin DSL 与 TOML catalog，确保每个依赖都可进入 UI 列表。
 */
class WorkspaceDependencyScanner(
    private val tomlParser: TomlCatalogParser,
    private val linker: DependencyLinker,
) {

  data class ScanResult(
      val repositories: List<com.itsaky.androidide.repository.dependencies.models.datas.ScopedRepositoryInfo>,
      val dependencies: List<ScopedDependencyInfo>,
      val catalogs: Map<String, VersionCatalog>,
  )

  fun scan(projectDir: File): ScanResult {
    val scriptFiles = findGradleScripts(projectDir)
    val repositories = mutableListOf<com.itsaky.androidide.repository.dependencies.models.datas.ScopedRepositoryInfo>()
    val rawDependencies = mutableListOf<ScopedDependencyInfo>()

    scriptFiles.forEach { script ->
      val analyzer = ScriptAnalyzerFactory.create(script)
      val (scriptRepos, scriptDeps) = analyzer.analyze(script)
      repositories += scriptRepos
      rawDependencies += scriptDeps
    }

    val catalogMap = loadCatalogs(projectDir)
    val linkedScriptDependencies = linker.link(rawDependencies, catalogMap)
    val catalogDeclaredDependencies = extractCatalogDeclaredDependencies(catalogMap)

    val allDependencies = linkedScriptDependencies + catalogDeclaredDependencies

    return ScanResult(
        repositories = repositories.distinctBy { "${it.url}:${it.declaredFile.absolutePath}" },
        dependencies = allDependencies,
        catalogs = catalogMap,
    )
  }

  private fun findGradleScripts(projectDir: File): List<File> {
    return projectDir
        .walkTopDown()
        .filter {
          it.isFile &&
              (it.name == "build.gradle" || it.name == "build.gradle.kts" || it.name == "settings.gradle" || it.name == "settings.gradle.kts") &&
              !it.inIgnoredDir(projectDir)
        }
        .toList()
  }

  private fun loadCatalogs(projectDir: File): Map<String, VersionCatalog> {
    val settingsAnalyzer = SettingsDslAnalyzer(projectDir)
    val catalogFiles = settingsAnalyzer.extractCatalogs()
    val catalogMap = linkedMapOf<String, VersionCatalog>()

    catalogFiles.forEach { (alias, file) ->
      catalogMap[alias] = tomlParser.parse(file)
    }

    return catalogMap
  }

  private fun extractCatalogDeclaredDependencies(
      catalogs: Map<String, VersionCatalog>
  ): List<ScopedDependencyInfo> {
    val result = mutableListOf<ScopedDependencyInfo>()

    catalogs.forEach { (alias, catalog) ->
      catalog.libraries.forEach { (libAlias, lib) ->
        val version = lib.versionLiteral ?: lib.versionRef?.let { ref -> catalog.versions[ref]?.value } ?: return@forEach
        val range =
            if (lib.versionRef != null) {
              catalog.versions[lib.versionRef]?.textRange
            } else {
              lib.textRange
            }

        result +=
            ScopedDependencyInfo(
                configuration = "versionCatalog($alias)",
                groupId = lib.group,
                artifactId = lib.name,
                version = version,
                declaredFile = catalog.sourceFile,
                declarationType = DeclarationType.CATALOG_ACCESSOR,
                statementTextRange = range,
                versionDefinitionFile = catalog.sourceFile,
                versionDefinitionRange = range,
                tomlReference = "${alias}.${libAlias}",
                versionReference = libAlias,
            )
      }
    }

    return result
  }

  private fun File.inIgnoredDir(projectDir: File): Boolean {
    val relative = this.relativeTo(projectDir).invariantSeparatorsPath
    return relative.contains("/build/") ||
        relative.startsWith("build/") ||
        relative.contains("/.gradle/") ||
        relative.startsWith(".gradle/") ||
        relative.contains("/.git/") ||
        relative.startsWith(".git/")
  }
}
