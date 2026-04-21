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

import com.itsaky.androidide.lsp.models.CompletionItem as IdeCompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind as IdeCompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat as IdeInsertTextFormat
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.lsp.models.TextEdit as IdeTextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.utils.Logger
import org.eclipse.lsp4j.CompletionItem as Lsp4jCompletionItem
import org.eclipse.lsp4j.CompletionItemKind as Lsp4jCompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat as Lsp4jInsertTextFormat
import org.eclipse.lsp4j.TextEdit as Lsp4jTextEdit

/**
 * 原生支持 LSP4J 的补全转换器。
 *
 * @author android_zero
 */
class KotlinCompletionConverter {

    companion object {
        private val log = Logger.instance("KotlinCompletionConverter")
    }

    private val snippetTransformer = SnippetTransformer()
    private val importResolver = KotlinImportResolver()
    private var javaCompilerBridge: KotlinJavaCompilerBridge? = null

    fun setJavaCompilerBridge(bridge: KotlinJavaCompilerBridge?) {
        this.javaCompilerBridge = bridge
    }

    /** 加工 LSP4J 传来的原始 Item 并加入 Android/Java 类库补充选项。 */
    fun convertWithClasspathEnhancement(
        items: List<Lsp4jCompletionItem>,
        fileContent: String,
        prefix: String
    ): List<IdeCompletionItem> {
        val results = mutableListOf<IdeCompletionItem>()

        for (item in items) {
            try {
                val converted = convertItemFast(item, fileContent)
                // 剔除 KLS 返回的毫无意义的 "K" 或 "Keyword" 占位补全项
                if (converted.ideLabel.isNotBlank() &&
                    converted.ideLabel != "K" &&
                    converted.ideLabel != "Keyword"
                ) {
                    results.add(converted)
                }
            } catch (e: Exception) {
                // 忽略无效的补全项
            }
        }

        // 如果用户输入了至少 1 个字符，向本地 Java/Android 类库请求跨层补全
        val classpathItems = if (prefix.length >= 1 && javaCompilerBridge != null) {
            getClasspathCompletions(prefix, fileContent)
        } else emptyList()

        // 去重合并
        return (results + classpathItems).distinctBy { "${it.ideLabel}:${it.detail}" }
    }

    /** 通过 JavaCompilerBridge 获取不在当前文件 import 列表中的可用类 */
    private fun getClasspathCompletions(prefix: String, fileContent: String): List<IdeCompletionItem> {
        val bridge = javaCompilerBridge ?: return emptyList()
        return try {
            val classes = bridge.findClassesByPrefix(prefix)
            classes.map { classInfo ->
                val needsImport = importResolver.needsImportForClass(
                    classInfo.simpleName,
                    classInfo.fullyQualifiedName,
                    fileContent
                )
                val additionalEdits = if (needsImport) {
                    val (line, importText) = importResolver.generateImportEdit(classInfo.fullyQualifiedName, fileContent)
                    listOf(
                        IdeTextEdit(
                            range = Range(start = Position(line, 0), end = Position(line, 0)),
                            newText = importText
                        )
                    )
                } else null

                IdeCompletionItem().apply {
                    ideLabel = classInfo.simpleName
                    detail = classInfo.fullyQualifiedName
                    insertText = classInfo.simpleName
                    insertTextFormat = IdeInsertTextFormat.PLAIN_TEXT
                    ideSortText = classInfo.simpleName
                    command = null
                    completionKind = IdeCompletionItemKind.CLASS
                    // 这种基于前缀搜索得来的类，严格标记为前缀匹配
                    matchLevel = MatchLevel.CASE_SENSITIVE_PREFIX
                    this.additionalTextEdits = additionalEdits
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 快速转换单个 LSP4J 补全节点为 AndroidIDE 模型 */
    private fun convertItemFast(item: Lsp4jCompletionItem, fileContent: String): IdeCompletionItem {
        val label = item.label ?: ""
        val detail = item.detail ?: ""
        
        // LSP4J 中，优先取 textEdit 中的文本，其次取 insertText，最后降级取 label
        var insertText = item.textEdit?.left?.newText ?: item.insertText ?: label
        val sortText = item.sortText
        
        val isSnippet = item.insertTextFormat == Lsp4jInsertTextFormat.Snippet

        if (isSnippet) {
            // 提取完整的参数键值对：Pair(名称, 类型)，用于支持高级语法特性推导
            val parameterPairs = snippetTransformer.extractParameterNames(detail)

            if (parameterPairs.isNotEmpty() && insertText.contains("\${")) {
                // 使用增强的智能联想转换，自动生成具名参数与尾随 Lambda
                insertText = snippetTransformer.transformSnippet(insertText, parameterPairs)
            }
            // 移除不可见的死占位符和修正括号排版
            insertText = snippetTransformer.cleanUpFormat(insertText)
        }

        val additionalEdits = mutableListOf<IdeTextEdit>()

        // 处理 LSP 服务器要求追加的引入包动作
        val serverImportEdit = extractImportFromAdditionalEdits(item.additionalTextEdits)
        if (serverImportEdit != null) {
            val (line, importText) = importResolver.generateImportEdit(serverImportEdit.replace("import ", ""), fileContent)
            additionalEdits.add(
                IdeTextEdit(
                    range = Range(start = Position(line, 0), end = Position(line, 0)),
                    newText = importText
                )
            )
        } else {
            // 检查我们在前端层面是否需要为其追加 import
            val tempItem = IdeCompletionItem().apply {
                ideLabel = label
                this.detail = detail
                this.insertText = insertText
                completionKind = mapCompletionKind(item.kind)
            }
            val fqn = importResolver.needsImport(tempItem, fileContent)
            if (fqn != null) {
                val (line, importText) = importResolver.generateImportEdit(fqn, fileContent)
                additionalEdits.add(
                    IdeTextEdit(
                        range = Range(start = Position(line, 0), end = Position(line, 0)),
                        newText = importText
                    )
                )
            }
        }

        // Snippet 类型的我们强行设定它的 Format 类型使得它被 IDE 的 SnippetController 接管
        val mappedFormat = if (isSnippet) IdeInsertTextFormat.SNIPPET else IdeInsertTextFormat.PLAIN_TEXT

        return IdeCompletionItem().apply {
            this.ideLabel = label
            this.detail = detail
            this.insertText = insertText
            this.insertTextFormat = mappedFormat
            this.ideSortText = sortText
            this.command = null
            this.completionKind = mapCompletionKind(item.kind)
            this.matchLevel = MatchLevel.NO_MATCH // 初始级别，在 Provider 层会经由过滤算法进行覆盖
            this.additionalTextEdits = if (additionalEdits.isNotEmpty()) additionalEdits else null
        }
    }

    /** 提取由 LSP4J 给出的 additionalTextEdits 里的 import 语句 */
    private fun extractImportFromAdditionalEdits(edits: List<Lsp4jTextEdit>?): String? {
        if (edits.isNullOrEmpty()) return null
        for (edit in edits) {
            val newText = edit.newText?.trim() ?: continue
            if (newText.startsWith("import ")) return newText
        }
        return null
    }

    /** 映射 LSP4J 标准补全类型枚举到 AndroidIDE 编辑器的枚举类型 */
    private fun mapCompletionKind(kind: Lsp4jCompletionItemKind?): IdeCompletionItemKind {
        return when (kind) {
            Lsp4jCompletionItemKind.Method -> IdeCompletionItemKind.METHOD
            Lsp4jCompletionItemKind.Function -> IdeCompletionItemKind.FUNCTION
            Lsp4jCompletionItemKind.Constructor -> IdeCompletionItemKind.CONSTRUCTOR
            Lsp4jCompletionItemKind.Field -> IdeCompletionItemKind.FIELD
            Lsp4jCompletionItemKind.Variable -> IdeCompletionItemKind.VARIABLE
            Lsp4jCompletionItemKind.Class -> IdeCompletionItemKind.CLASS
            Lsp4jCompletionItemKind.Interface -> IdeCompletionItemKind.INTERFACE
            Lsp4jCompletionItemKind.Module -> IdeCompletionItemKind.MODULE
            Lsp4jCompletionItemKind.Property -> IdeCompletionItemKind.PROPERTY
            Lsp4jCompletionItemKind.Value -> IdeCompletionItemKind.VALUE
            Lsp4jCompletionItemKind.Enum -> IdeCompletionItemKind.ENUM
            Lsp4jCompletionItemKind.Keyword -> IdeCompletionItemKind.KEYWORD
            Lsp4jCompletionItemKind.Snippet -> IdeCompletionItemKind.SNIPPET
            Lsp4jCompletionItemKind.EnumMember -> IdeCompletionItemKind.ENUM_MEMBER
            Lsp4jCompletionItemKind.TypeParameter -> IdeCompletionItemKind.TYPE_PARAMETER
            else -> IdeCompletionItemKind.NONE
        }
    }
}
