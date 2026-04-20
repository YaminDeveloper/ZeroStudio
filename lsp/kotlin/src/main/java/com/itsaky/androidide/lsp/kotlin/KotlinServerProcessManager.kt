/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.kotlin

import android.content.Context
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.kotlin.events.KotlinLanguageClientImpl
import com.itsaky.androidide.lsp.kotlin.events.KotlinTextDocumentSyncHandler
import com.itsaky.androidide.lsp.kotlin.settings.KotlinServerSettings
import com.itsaky.androidide.lsp.kotlin.ui.KotlinServerConstants
import com.itsaky.androidide.lsp.kotlin.ui.events.LspEventBus
import com.itsaky.androidide.lsp.kotlin.ui.events.LspInstallRequestEvent
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 核心重构：基于 LSP4J 流管道连接的 Kotlin Language Server 进程管理器。
 *
 * @author android_zero
 */
class KotlinServerProcessManager(private val context: Context) {

    private var serverProcess: Process? = null
    private var currentServerImpl: KotlinLanguageServerImpl? = null
    private var launcher: Launcher<org.eclipse.lsp4j.services.LanguageServer>? = null
    
    // 注入的全局 AndroidIDE 客户端实现，用来做 UI 交互（如报错提示框、代码替换）
    private val kotlinClient: KotlinLanguageClientImpl by lazy { KotlinLanguageClientImpl() }
    
    // 强大的 Android 构建系统动态环境路径解析支持
    private var classpathProvider: KotlinClasspathProvider? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private val log = LoggerFactory.getLogger(KotlinServerProcessManager::class.java)
    }

    fun setClasspathProvider(provider: KotlinClasspathProvider) {
        this.classpathProvider = provider
    }

    /**
     * 启动 Kotlin LSP 服务进程，挂载 LSP4J 通讯协议并向全局注册
     */
    fun startServer() {
        KotlinTextDocumentSyncHandler.init()
        coroutineScope.launch {
            if (checkInstallation()) {
                launchProcessAndRegister()
            } else {
                requestInstallation()
            }
        }
    }

    private fun checkInstallation(): Boolean {
        val binDir = File(Environment.KOTLIN_LSP_HOME, "bin")
        val launcher = File(binDir, KotlinServerConstants.LAUNCHER_SCRIPT_NAME)
        
        if (!launcher.exists()) return false

        val libDir = File(Environment.KOTLIN_LSP_HOME, "lib")
        if (!libDir.exists() || !libDir.isDirectory) return false

        val existingJars = libDir.list()?.toSet() ?: emptySet()
        for (requiredJar in KotlinServerConstants.REQUIRED_LIB_JARS) {
            if (!existingJars.contains(requiredJar)) {
                log.warn("Missing required jar: $requiredJar")
                return false
            }
        }
        return true
    }

    private fun requestInstallation() {
        val installEvent = LspInstallRequestEvent(
            serverId = "kotlin-lsp",
            serverName = "Kotlin Language Server",
            dialogTitle = "Kotlin Language Server",
            dialogMessage = "The Kotlin Language Server is required to provide code completion, diagnostics, and navigation features for Kotlin files. Do you want to download and install it now? (Size: ~50MB)",
            downloadUrl = KotlinServerConstants.DOWNLOAD_URL,
            installPath = Environment.KOTLIN_LSP_HOME,
            onInstallComplete = {
                log.info("Installation completed, starting server...")
                coroutineScope.launch { launchProcessAndRegister() }
            },
            onInstallCancelled = { log.warn("User cancelled the Kotlin LSP installation.") }
        )
        LspEventBus.postInstallRequest(installEvent)
    }

    private suspend fun launchProcessAndRegister() {
        try {
            if (serverProcess?.isAlive == true) {
                log.info("Kotlin LSP process is already running.")
                return
            }

            val binDir = File(Environment.KOTLIN_LSP_HOME, "bin")
            val launcherScript = File(binDir, KotlinServerConstants.LAUNCHER_SCRIPT_NAME)
            launcherScript.setExecutable(true, false)
            
            if (!launcherScript.canExecute()) {
                throw IllegalStateException("Kotlin LSP launcher is not executable: ${launcherScript.absolutePath}")
            }

            // 获取 Android 环境所需的 Classpath
            val androidClasspath = classpathProvider?.getClasspath() ?: ""

            // 构建原生 Process 进程：极其重要，必须将 redirectErrorStream 设置为 false！
            // 因为 KLS 的日志和异常输出在 stderr 中，如果合并到 stdout(LSP管道)，会导致 LSP4J JSON 解析崩溃！
            val pb = ProcessBuilder("sh", launcherScript.absolutePath).apply {
                directory(Environment.KOTLIN_LSP_HOME)
                environment().apply {
                    put("JAVA_HOME", Environment.JAVA_HOME.absolutePath)
                    put("PATH", "${Environment.BIN_DIR.absolutePath}:${Environment.JAVA_HOME.absolutePath}/bin:${System.getenv("PATH")}")
                    val javaToolOptions =
                        listOf(
                                get("JAVA_TOOL_OPTIONS"),
                                "-Dsqlite.purejava=true",
                                "-Dorg.sqlite.tmpdir=${context.cacheDir.absolutePath}",
                            )
                            .filterNotNull()
                            .joinToString(" ")
                            .trim()
                    put("JAVA_TOOL_OPTIONS", javaToolOptions)
                    put("ORG_SQLITE_PUREJAVA", "true")
                    // 传递环境变量使 Server 使用我们在外部计算的 Classpath
                    put("KOTLIN_LSP_DISABLE_DEPENDENCY_RESOLUTION", "true")
                    put("KOTLIN_LSP_USE_PREDEFINED_CLASSPATH", "true")
                    put("KOTLIN_LSP_CLASSPATH", androidClasspath)
                    put("CLASSPATH", androidClasspath)
                }
                redirectErrorStream(false) // 绝对禁止合并流！
            }

            serverProcess = pb.start()
            log.info("Kotlin LSP Native Process started successfully. PID: ${serverProcess?.hashCode()}")
            startStderrMonitor(serverProcess!!)

            // 初始化 LSP4J 通讯协议机制
            currentServerImpl?.shutdown()
            
            val newServerImpl = KotlinLanguageServerImpl(process = serverProcess!!)
            val remoteProxy = createLsp4jProxy(serverProcess!!, newServerImpl)
            newServerImpl.bindRemoteServer(remoteProxy)
            
            newServerImpl.connectClient(kotlinClient)
            currentServerImpl = newServerImpl

            // 全局注册与挂载
            val registry = ILanguageServerRegistry.getDefault()
            registry.register(newServerImpl)
            
            bindWorkspaceWhenReady()
            log.info("KotlinLanguageServerImpl successfully connected to LSP4J and registered to IDE.")
        } catch (e: Exception) {
            log.error("Failed to launch Kotlin LSP Process via Native ProcessBuilder", e)
        }
    }

    /**
     * 核心挂载器：创建并启动 LSP4J 协议桥
     */
    private fun createLsp4jProxy(
        process: Process,
        serverImpl: KotlinLanguageServerImpl
    ): org.eclipse.lsp4j.services.LanguageServer {
        val clientEndpoint = serverImpl.clientEndpoint
        launcher = LSPLauncher.createClientLauncher(
            clientEndpoint,
            process.inputStream,
            process.outputStream
        )
        
        // 启动异步线程窃听服务器返回
        launcher?.startListening()
        
        return launcher!!.remoteProxy
    }

    private fun bindWorkspaceWhenReady() {
        coroutineScope.launch {
            repeat(20) { attempt ->
                val workspace = IProjectManager.getInstance().getWorkspace()
                if (workspace != null) {
                    currentServerImpl?.setupWorkspace(workspace)
                    if (currentServerImpl?.isReady() == true) {
                        currentServerImpl?.applySettings(KotlinServerSettings())
                        log.info("Kotlin LSP workspace has been initialized and synchronized via LSP4J.")
                    } else {
                        log.warn(
                            "Kotlin LSP workspace binding attempted, but server initialization failed: {}",
                            currentServerImpl?.getLastInitError() ?: "unknown error"
                        )
                    }
                    return@launch
                }
                if (attempt < 19) {
                    delay(300)
                }
            }
            log.warn("Workspace unavailable while bootstrapping Kotlin LSP. Waiting for project events.")
        }
    }

    private fun startStderrMonitor(process: Process) {
        coroutineScope.launch {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            log.debug("Kotlin LSP stderr: $line")
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore stream close during shutdown
            }
        }
    }

    fun stopServer() {
        currentServerImpl?.shutdown()
        serverProcess?.destroy()
        serverProcess = null
        currentServerImpl = null
        launcher = null
    }
}
