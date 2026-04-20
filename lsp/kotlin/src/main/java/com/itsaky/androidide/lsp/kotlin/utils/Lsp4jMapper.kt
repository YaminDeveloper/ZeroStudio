/*
 *  This file is part of AndroidIDE.
 */
package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.lsp.models.InsertTextFormat
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.eclipse.lsp4j.Diagnostic

/**
 * 核心：AndroidIDE 自定义 LSP 模型与 Eclipse LSP4J 标准模型的双向映射器。
 * 
 * @author android_zero
 */
object Lsp4jMapper {

    // --- Position & Range Mapping ---

    fun Position.toLsp4j(): org.eclipse.lsp4j.Position {
        return org.eclipse.lsp4j.Position(this.line, this.column)
    }

    fun org.eclipse.lsp4j.Position.toIde(): Position {
        return Position(this.line, this.character)
    }

    fun Range.toLsp4j(): org.eclipse.lsp4j.Range {
        return org.eclipse.lsp4j.Range(this.start.toLsp4j(), this.end.toLsp4j())
    }

    fun org.eclipse.lsp4j.Range.toIde(): Range {
        return Range(this.start.toIde(), this.end.toIde())
    }

    // --- TextEdit Mapping ---

    fun TextEdit.toLsp4j(): org.eclipse.lsp4j.TextEdit {
        return org.eclipse.lsp4j.TextEdit(this.range.toLsp4j(), this.newText)
    }

    fun org.eclipse.lsp4j.TextEdit.toIde(): TextEdit {
        return TextEdit(this.range.toIde(), this.newText)
    }

    // --- Diagnostic Mapping ---

    fun Diagnostic.toIde(uri: String): com.itsaky.androidide.lsp.models.DiagnosticItem {
        val severity = when (this.severity) {
            org.eclipse.lsp4j.DiagnosticSeverity.Error -> DiagnosticSeverity.ERROR
            org.eclipse.lsp4j.DiagnosticSeverity.Warning -> DiagnosticSeverity.WARNING
            org.eclipse.lsp4j.DiagnosticSeverity.Information -> DiagnosticSeverity.INFO
            org.eclipse.lsp4j.DiagnosticSeverity.Hint -> DiagnosticSeverity.HINT
            else -> DiagnosticSeverity.INFO
        }
        
        return com.itsaky.androidide.lsp.models.DiagnosticItem(
            this.message,
            this.code?.left ?: "unknown",
            this.range.toIde(),
            this.source ?: "kotlin-lsp",
            severity,
            emptyList()
        )
    }

    // --- Completion Mapping ---

    fun org.eclipse.lsp4j.CompletionItem.toIde(): com.itsaky.androidide.lsp.models.CompletionItem {
        val ideKind = mapCompletionKind(this.kind)
        val ideFormat = if (this.insertTextFormat == org.eclipse.lsp4j.InsertTextFormat.Snippet) {
            InsertTextFormat.SNIPPET
        } else {
            InsertTextFormat.PLAIN_TEXT
        }

        val textToInsert = this.textEdit?.left?.newText ?: this.insertText ?: this.label

        return com.itsaky.androidide.lsp.models.CompletionItem().apply {
            this.ideLabel = this@toIde.label
            this.detail = this@toIde.detail ?: ""
            this.insertText = textToInsert
            this.insertTextFormat = ideFormat
            this.ideSortText = this@toIde.sortText ?: this@toIde.label
            this.completionKind = ideKind
            this.matchLevel = com.itsaky.androidide.lsp.models.MatchLevel.NO_MATCH // 将由 Provider 评估
            
            // 处理 additionalTextEdits (例如自动导包)
            if (this@toIde.additionalTextEdits != null) {
                this.additionalTextEdits = this@toIde.additionalTextEdits.map { it.toIde() }
            }
            
            // 剔除无用的占位文档
            if (this.ideLabel == "K" || this.ideLabel == "Keyword") {
                this.completionKind = CompletionItemKind.NONE
            }
        }
    }

    private fun mapCompletionKind(kind: org.eclipse.lsp4j.CompletionItemKind?): CompletionItemKind {
        return when (kind) {
            org.eclipse.lsp4j.CompletionItemKind.Method -> CompletionItemKind.METHOD
            org.eclipse.lsp4j.CompletionItemKind.Function -> CompletionItemKind.FUNCTION
            org.eclipse.lsp4j.CompletionItemKind.Constructor -> CompletionItemKind.CONSTRUCTOR
            org.eclipse.lsp4j.CompletionItemKind.Field -> CompletionItemKind.FIELD
            org.eclipse.lsp4j.CompletionItemKind.Variable -> CompletionItemKind.VARIABLE
            org.eclipse.lsp4j.CompletionItemKind.Class -> CompletionItemKind.CLASS
            org.eclipse.lsp4j.CompletionItemKind.Interface -> CompletionItemKind.INTERFACE
            org.eclipse.lsp4j.CompletionItemKind.Module -> CompletionItemKind.MODULE
            org.eclipse.lsp4j.CompletionItemKind.Property -> CompletionItemKind.PROPERTY
            org.eclipse.lsp4j.CompletionItemKind.Value -> CompletionItemKind.VALUE
            org.eclipse.lsp4j.CompletionItemKind.Enum -> CompletionItemKind.ENUM
            org.eclipse.lsp4j.CompletionItemKind.Keyword -> CompletionItemKind.KEYWORD
            org.eclipse.lsp4j.CompletionItemKind.Snippet -> CompletionItemKind.SNIPPET
            org.eclipse.lsp4j.CompletionItemKind.EnumMember -> CompletionItemKind.ENUM_MEMBER
            org.eclipse.lsp4j.CompletionItemKind.TypeParameter -> CompletionItemKind.TYPE_PARAMETER
            else -> CompletionItemKind.NONE
        }
    }
}
