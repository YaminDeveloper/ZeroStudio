package com.itsaky.androidide.compose.preview.compiler

import android.content.Context
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class ComposeClasspathManager(private val context: Context) {

    private val composeDir: File
        get() = Environment.COMPOSE_HOME

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposeClasspathManager::class.java)

        private const val D8_HEAP_SIZE = "512m"
        private const val MIN_API_LEVEL = "21"
        private const val D8_TIMEOUT_MINUTES = 5L
    }

    private val runtimeDexDir: File
        get() = File(composeDir, "dex")

    private val gradleModuleCache: File
        get() = File(Environment.HOME, ".gradle/caches/modules-2/files-2.1")

    private val dexMutex = Mutex()

    private val kotlinArtifacts = mapOf(
        "kotlin-compiler" to Pair("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
        "kotlin-compiler-runner" to Pair("org.jetbrains.kotlin", "kotlin-compiler-runner"),
        "kotlin-stdlib" to Pair("org.jetbrains.kotlin", "kotlin-stdlib"),
        "kotlin-reflect" to Pair("org.jetbrains.kotlin", "kotlin-reflect"),
        "kotlin-script-runtime" to Pair("org.jetbrains.kotlin", "kotlin-script-runtime"),
        "trove4j" to Pair("org.jetbrains.intellij.deps", "trove4j"),
        "annotations" to Pair("org.jetbrains", "annotations"),
        "kotlinx-coroutines-core" to Pair("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
    )

    private val requiredCompilerArtifactKeys = listOf(
        "kotlin-compiler",
        "kotlin-compiler-runner",
        "kotlin-stdlib",
        "kotlin-reflect",
        "kotlin-script-runtime",
        "trove4j",
        "annotations",
        "kotlinx-coroutines-core"
    )

    private var projectKotlinVersion: String? = null
    private var projectCoroutinesVersion: String? = null

    private val requiredRuntimeJarPatterns = listOf<Any>(
        "compose-compiler-plugin.jar",
        Regex("runtime-release\\.jar"),
        Regex("ui-release\\.jar"),
        Regex("animation-release\\.jar"),
        Regex("animation-core-release\\.jar"),
        Regex("foundation-release\\.jar"),
        Regex("material3-release\\.jar")
    )

    fun ensureComposeJarsExtracted(): Boolean {
        val extracted = areRuntimeJarsExtracted()
        LOG.info("Compose runtime JARs extracted: {}, dir: {}", extracted, composeDir.absolutePath)

        if (extracted) {
            LOG.debug("Compose runtime JARs already extracted")
            return true
        }

        return try {
            composeDir.deleteRecursively()
            extractComposeJars()
            true
        } catch (e: Exception) {
            LOG.error("Failed to extract Compose JARs", e)
            false
        }
    }

    fun isKotlinCompilerAvailable(): Boolean {
        val compiler = findGradleCacheJar("kotlin-compiler")
        val available = compiler?.exists() == true
        LOG.info("Kotlin compiler available in Gradle cache: {}", available)
        return available
    }

    private fun areRuntimeJarsExtracted(): Boolean {
        if (!composeDir.exists()) return false

        val files = composeDir.listFiles()?.map { it.name } ?: return false

        return requiredRuntimeJarPatterns.all { pattern ->
            when (pattern) {
                is String -> files.contains(pattern)
                is Regex -> files.any { pattern.matches(it) }
                else -> false
            }
        }
    }

    private fun findGradleCacheJar(artifactKey: String): File? {
        val coordinates = kotlinArtifacts[artifactKey] ?: return null
        val version = resolveArtifactVersion(artifactKey, coordinates.first, coordinates.second) ?: return null
        return findGradleCacheJar(coordinates.first, coordinates.second, version)
    }

    private fun resolveArtifactVersion(artifactKey: String, groupId: String, artifactId: String): String? {
        return when (artifactKey) {
            "kotlin-compiler",
            "kotlin-compiler-runner",
            "kotlin-stdlib",
            "kotlin-reflect",
            "kotlin-script-runtime" -> projectKotlinVersion ?: findLatestArtifactVersion(groupId, artifactId)
            "kotlinx-coroutines-core" -> projectCoroutinesVersion ?: findLatestArtifactVersion(groupId, artifactId)
            "trove4j" -> "1.0.20200330"
            "annotations" -> "24.1.0"
            else -> findLatestArtifactVersion(groupId, artifactId)
        }
    }

    private fun findLatestArtifactVersion(groupId: String, artifactId: String): String? {
        val artifactRoot = File(gradleModuleCache, "$groupId/$artifactId")
        val versions = artifactRoot.listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?.sortedWith { a, b -> compareVersionStrings(b, a) }
            ?: return null
        return versions.firstOrNull()
    }

    private fun compareVersionStrings(left: String, right: String): Int {
        val separators = "[.-]".toRegex()
        val leftParts = left.split(separators)
        val rightParts = right.split(separators)
        val max = maxOf(leftParts.size, rightParts.size)
        for (i in 0 until max) {
            val l = leftParts.getOrNull(i) ?: "0"
            val r = rightParts.getOrNull(i) ?: "0"
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val cmp = when {
                ln != null && rn != null -> ln.compareTo(rn)
                else -> l.compareTo(r)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun configureFromProjectClasspath(classpaths: List<File>) {
        projectKotlinVersion = classpaths
            .firstNotNullOfOrNull { file ->
                Regex("""kotlin-(?:stdlib(?:-jdk\d+)?|compiler-embeddable)-(.+)\.jar$""")
                    .find(file.name)
                    ?.groupValues
                    ?.get(1)
            }

        projectCoroutinesVersion = classpaths
            .firstNotNullOfOrNull { file ->
                Regex("""kotlinx-coroutines-core(?:-jvm)?-(.+)\.jar$""")
                    .find(file.name)
                    ?.groupValues
                    ?.get(1)
            }

        LOG.info(
            "Configured Kotlin artifact versions from project classpath: kotlin={}, coroutines={}",
            projectKotlinVersion ?: "auto",
            projectCoroutinesVersion ?: "auto"
        )
    }

    private fun findGradleCacheJar(groupId: String, artifactId: String, version: String): File? {
        val artifactDir = File(gradleModuleCache, "$groupId/$artifactId/$version")

        if (!artifactDir.exists()) {
            LOG.debug("Gradle cache artifact dir not found: {}", artifactDir)
            return null
        }

        val jarFileName = "$artifactId-$version.jar"
        val hashDirs = artifactDir.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: return null

        for (hashDir in hashDirs) {
            val jar = File(hashDir, jarFileName)
            if (jar.exists()) {
                LOG.debug(
                    "Found {}:{}:{} in Gradle cache hash dir {}: {}",
                    groupId,
                    artifactId,
                    version,
                    hashDir.name,
                    jar
                )
                return jar
            }
        }

        return null
    }

    private fun extractComposeJars() {
        composeDir.mkdirs()
        val composeDirPath = composeDir.canonicalPath

        context.assets.open("compose/compose-jars.zip").use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }

                    val file = File(composeDir, entry.name).canonicalFile
                    if (!file.path.startsWith(composeDirPath)) {
                        LOG.warn("Skipping zip entry with invalid path: {}", entry.name)
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }

                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        zip.copyTo(output)
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        LOG.info("Extracted Compose JARs to {}", composeDir)
    }

    fun getKotlinCompiler(): File? {
        return findGradleCacheJar("kotlin-compiler")
    }

    suspend fun ensureCompilerArtifactsAvailable(): Boolean = withContext(Dispatchers.IO) {
        requiredCompilerArtifactKeys.all { key -> findGradleCacheJar(key)?.exists() == true }
    }

    fun getCompilerPlugin(): File {
        return File(composeDir, "compose-compiler-plugin.jar")
    }

    fun getKotlinStdlib(): File? {
        return findGradleCacheJar("kotlin-stdlib")
    }

    fun getCompilerBootstrapClasspath(): String {
        val jars = buildList {
            findGradleCacheJar("kotlin-compiler")?.let { add(it) }
            findGradleCacheJar("kotlin-compiler-runner")?.let { add(it) }
            findGradleCacheJar("kotlin-stdlib")?.let { add(it) }
            findGradleCacheJar("kotlin-reflect")?.let { add(it) }
            findGradleCacheJar("kotlin-script-runtime")?.let { add(it) }
            findGradleCacheJar("trove4j")?.let { add(it) }
            findGradleCacheJar("annotations")?.let { add(it) }
            findGradleCacheJar("kotlinx-coroutines-core")?.let { add(it) }
        }
        return jars.filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }
    }

    fun getRuntimeJars(): List<File> {
        val compilerPlugin = getCompilerPlugin()
        return composeDir.listFiles { file ->
            file.extension == "jar" && file != compilerPlugin
        }?.toList() ?: emptyList()
    }

    fun getAllJars(): List<File> {
        return buildList {
            addAll(getRuntimeJars())
            findGradleCacheJar("kotlin-stdlib")?.let { add(it) }
        }
    }

    fun getFullClasspath(): List<File> {
        return buildList {
            add(Environment.ANDROID_JAR)
            addAll(getAllJars())
        }
    }

    fun getCompilationClasspath(additionalJars: List<File> = emptyList()): String {
        val base = getFullClasspath()
        val extra = additionalJars.filter { it.exists() }
        val missingExtra = additionalJars.filter { !it.exists() }
        val all = (base + extra).filter { it.exists() }
        val classpath = all.joinToString(File.pathSeparator) { it.absolutePath }
        LOG.info("Compilation classpath has {} JARs ({} bundled, {} project, {} missing)", all.size, base.count { it.exists() }, extra.size, missingExtra.size)
        return classpath
    }

    fun getD8Jar(): File? = findD8Jar()

    suspend fun getOrCreateRuntimeDex(): File? = dexMutex.withLock {
        withContext(Dispatchers.IO) {
            LOG.info("getOrCreateRuntimeDex called, runtimeDexDir={}", runtimeDexDir.absolutePath)
            val runtimeDex = File(runtimeDexDir, "compose-runtime.dex")

            if (runtimeDex.exists()) {
                LOG.info("Using cached Compose runtime DEX: {}", runtimeDex.absolutePath)
                return@withContext runtimeDex
            }

            LOG.info("Creating Compose runtime DEX (one-time operation)...")

            val runtimeJars = getRuntimeJars()
            if (runtimeJars.isEmpty()) {
                LOG.error("No runtime JARs found to dex")
                return@withContext null
            }

            val d8Jar = findD8Jar()
            if (d8Jar == null) {
                LOG.error("D8 jar not found")
                return@withContext null
            }

            val javaExecutable = Environment.JAVA
            if (!javaExecutable.exists()) {
                LOG.error("Java executable not found")
                return@withContext null
            }

            runtimeDexDir.mkdirs()

            val command = buildList {
                add(javaExecutable.absolutePath)
                add("-Xmx$D8_HEAP_SIZE")
                add("-cp")
                add(d8Jar.absolutePath)
                add("com.android.tools.r8.D8")
                add("--release")
                add("--min-api")
                add(MIN_API_LEVEL)
                add("--lib")
                add(Environment.ANDROID_JAR.absolutePath)
                add("--output")
                add(runtimeDexDir.absolutePath)
                runtimeJars.forEach { jar ->
                    add(jar.absolutePath)
                }
            }

            LOG.info("Running D8 for runtime JARs: {} JARs", runtimeJars.size)

            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val completed =
                    process.waitFor(
                        D8_TIMEOUT_MINUTES * 60,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                val output = process.inputStream.bufferedReader().use { it.readText() }

                if (!completed) {
                    process.destroyForcibly()
                    LOG.error("D8 timed out after {} minutes. Output: {}", D8_TIMEOUT_MINUTES, output)
                    return@withContext null
                }

                val exitCode = process.exitValue()
                val outputDex = File(runtimeDexDir, "classes.dex")
                if (exitCode == 0 && outputDex.exists()) {
                    outputDex.renameTo(runtimeDex)
                    LOG.info("Compose runtime DEX created successfully")
                    return@withContext runtimeDex
                } else {
                    LOG.error("D8 failed for runtime. Exit: {}, output: {}", exitCode, output)
                    return@withContext null
                }
            } catch (e: Exception) {
                LOG.error("Failed to create runtime DEX", e)
                return@withContext null
            }
        }
    }

    private fun findD8Jar(): File? {
        val buildToolsDir = File(Environment.ANDROID_HOME, "build-tools")
        if (!buildToolsDir.exists()) {
            LOG.warn("Build tools directory not found: {}", buildToolsDir)
            return null
        }

        val installedVersions = buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (versionDir in installedVersions) {
            val d8Jar = File(versionDir, "lib/d8.jar")
            if (d8Jar.exists()) {
                LOG.debug("Using D8 from build-tools {}", versionDir.name)
                return d8Jar
            }
        }

        LOG.warn("D8 jar not found in any installed build-tools version")
        return null
    }

}
