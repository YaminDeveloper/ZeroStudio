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

package com.itsaky.androidide.lsp.kotlin.events

import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServerImpl
import com.itsaky.androidide.lsp.models.*
import com.itsaky.androidide.utils.Logger
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Kotlin 文档生命周期同步处理器。
 *
 * @author android_zero
 */
object KotlinTextDocumentSyncHandler {

  private val log = Logger.instance("KotlinTextDocumentSyncHandler")
  private val openedDocs = ConcurrentHashMap<Path, DocumentSnapshot>()
  private val openedOnServer = ConcurrentHashMap.newKeySet<Path>()

  private data class DocumentSnapshot(
      val languageId: String = "kotlin",
      val version: Int,
      val text: String,
  )

  /** 在主程序或管理器初始化时调用一次以注册 EventBus */
  fun init() {
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
      log.info("KotlinTextDocumentSyncHandler registered to EventBus.")
    }
  }

  fun onServerReady() {
    val server = getServer() ?: return
    openedOnServer.clear()
    openedDocs.forEach { (path, snapshot) ->
      dispatchDidOpen(server, path, snapshot)
    }
  }

  private fun getServer(): KotlinLanguageServerImpl? {
    return ILanguageServerRegistry.getDefault().getServer("kotlin-lsp") as? KotlinLanguageServerImpl
  }

  private fun isKotlinFile(path: String): Boolean {
    return path.endsWith(".kt", ignoreCase = true) || path.endsWith(".kts", ignoreCase = true)
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentOpen(event: DocumentOpenEvent) {
    if (!isKotlinFile(event.openedFile.toString())) return
    openedDocs[event.openedFile] =
        DocumentSnapshot(
            languageId = "kotlin",
            version = event.version,
            text = event.text ?: "",
        )

    val server = getServer() ?: return

    dispatchDidOpen(server, event.openedFile, openedDocs[event.openedFile] ?: return)
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentChange(event: DocumentChangeEvent) {
    if (!isKotlinFile(event.changedFile.toString())) return
    val newText = event.newText ?: ""
    openedDocs[event.changedFile] =
        DocumentSnapshot(
            languageId = "kotlin",
            version = event.version,
            text = newText,
        )

    val server = getServer() ?: return
    val path = event.changedFile
    val snapshot = openedDocs[path] ?: return
    if (!openedOnServer.contains(path)) {
      // 某些情况下（服务重启/时序竞态）会先收到 didChange，再收到 didOpen。这里兜底补发。
      dispatchDidOpen(server, path, snapshot)
      if (!openedOnServer.contains(path)) return
    }

    // AndroidIDE 当前提供的是全量替换事件 (newText)，我们将整个文本作为一个 ContentChangeEvent 下发
    val changeEvent = TextDocumentContentChangeEvent(text = newText)
    server.didChange(
        DidChangeTextDocumentParams(
            file = path,
            version = event.version,
            contentChanges = listOf(changeEvent),
        )
    )
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentClose(event: DocumentCloseEvent) {
    if (!isKotlinFile(event.closedFile.toString())) return
    openedDocs.remove(event.closedFile)
    openedOnServer.remove(event.closedFile)
    val server = getServer() ?: return

    server.didClose(DidCloseTextDocumentParams(file = event.closedFile))
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentSave(event: DocumentSaveEvent) {
    if (!isKotlinFile(event.file.toString())) return
    val server = getServer() ?: return

    server.didSave(
        DidSaveTextDocumentParams(
            file = event.file, // changed to Path from File via definition mapping
            reason = TextDocumentSaveReason.Manual,
            text = null,
        )
    )
  }

  private fun dispatchDidOpen(server: KotlinLanguageServerImpl, path: Path, snapshot: DocumentSnapshot) {
    runCatching {
          server.didOpen(
              DidOpenTextDocumentParams(
                  file = path,
                  languageId = snapshot.languageId,
                  version = snapshot.version,
                  text = snapshot.text,
              ),
          )
          openedOnServer.add(path)
        }
        .onFailure { log.error("Failed to send didOpen for ${path.fileName}", it) }
  }
}
