/*
 *  This file is part of AndroidIDE.
 */
package com.itsaky.androidide.lsp.kotlin.events

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.utils.Lsp4jMapper.toIde
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.MessageType
import com.itsaky.androidide.lsp.models.ShowMessageParams
import com.itsaky.androidide.lsp.models.WorkspaceEdit
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * 实现标准的 lsp4j LanguageClient 接口。
 * 负责接收 KLS 服务端的反向调用（如诊断推送、展示提示、应用重构代码），并路由至 AndroidIDE 的 ILanguageClient。
 * 
 * @author android_zero
 */
class KotlinLanguageClientEndpoint(private val ideClient: ILanguageClient) : LanguageClient {

    override fun telemetryEvent(any: Any) {}

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        try {
            val uriStr = params.uri
            val path = File(URI(uriStr)).toPath()
            val ideDiagnostics = params.diagnostics.map { it.toIde(uriStr) }
            ideClient.publishDiagnostics(DiagnosticResult(path, ideDiagnostics))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun showMessage(messageParams: MessageParams) {
        val type = when (messageParams.type) {
            org.eclipse.lsp4j.MessageType.Error -> MessageType.Error
            org.eclipse.lsp4j.MessageType.Warning -> MessageType.Warning
            org.eclipse.lsp4j.MessageType.Info -> MessageType.Info
            else -> MessageType.Log
        }
        ideClient.showMessage(ShowMessageParams(type, messageParams.message))
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<org.eclipse.lsp4j.MessageActionItem> {
        return CompletableFuture.completedFuture(null)
    }

    override fun logMessage(message: MessageParams) {
        // Log to internal
    }

    override fun applyEdit(params: org.eclipse.lsp4j.ApplyWorkspaceEditParams): CompletableFuture<org.eclipse.lsp4j.ApplyWorkspaceEditResponse> {
        val lspEdit = params.edit
        val ideDocumentChanges = mutableListOf<com.itsaky.androidide.lsp.models.DocumentChange>()
        
        lspEdit.changes?.forEach { (uri, edits) ->
            val path = File(URI(uri)).toPath()
            val ideEdits = edits.map { it.toIde() }
            ideDocumentChanges.add(com.itsaky.androidide.lsp.models.DocumentChange(path, ideEdits))
        }

        val success = ideClient.applyWorkspaceEdit(WorkspaceEdit(ideDocumentChanges))
        return CompletableFuture.completedFuture(org.eclipse.lsp4j.ApplyWorkspaceEditResponse(success))
    }
}