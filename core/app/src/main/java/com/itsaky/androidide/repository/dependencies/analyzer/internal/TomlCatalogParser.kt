/*
 * @author android_zero
 */
package com.itsaky.androidide.repository.dependencies.analyzer.internal

import com.itsaky.androidide.lexers.toml.TomlLexer
import com.itsaky.androidide.lexers.toml.TomlParser
import com.itsaky.androidide.lexers.toml.TomlParserBaseListener
import com.itsaky.androidide.repository.dependencies.models.datas.CatalogLibrarys
import com.itsaky.androidide.repository.dependencies.models.datas.CatalogPlugin
import com.itsaky.androidide.repository.dependencies.models.datas.CatalogVersion
import com.itsaky.androidide.repository.dependencies.models.datas.TextRange
import com.itsaky.androidide.repository.dependencies.models.datas.VersionCatalog
import java.io.File
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker

/**
 * <h1>基于 ANTLR4 的高精度 TOML 解析器</h1>
 *
 * <p>
 * 该解析器使用 AndroidIDE 内置的 <code>com.itsaky.androidide.lexers.toml</code> (ANTLR4) 对 Gradle Version
 * Catalog 文件进行词法和语法分析。 </p>
 *
 * <h3>核心特性:</h3>
 * <ul>
 * <li><b>原生解析：</b> 直接使用 ANTLR4 生成的 Parser，无需外部依赖或复杂的 PSI 环境。</li>
 * <li><b>精准定位：</b> 利用 ANTLR Token 的 startIndex 和 stopIndex 计算精确的文件修改位置。</li>
 * <li><b>健壮性：</b> 能够正确处理 TOML 的标准表 (<code>[table]</code>)、内联表 (<code>{...}</code>) 以及各种字符串格式。</li>
 * </ul>
 */
class TomlCatalogParser {

  /** 解析 TOML 文件。 */
  fun parse(file: File): VersionCatalog {
    if (!file.exists()) {
      return VersionCatalog(emptyMap(), emptyMap(), emptyMap(), file)
    }

    val text = file.readText()
    val charStream = CharStreams.fromString(text)

    // 词法分析
    val lexer = TomlLexer(charStream)
    val tokenStream = CommonTokenStream(lexer)

    // 语法分析
    val parser = TomlParser(tokenStream)
    // 移除默认的错误监听器
    parser.removeErrorListeners()

    val tree = parser.document() // document 是 TOML 语法的根规则

    // 遍历语法树提取数据
    val listener = CatalogListener(text)
    ParseTreeWalker.DEFAULT.walk(listener, tree)

    return VersionCatalog(listener.versions, listener.libraries, listener.plugins, file)
  }

  /** 内部监听器，用于遍历 AST 并构建内存模型 */
  private class CatalogListener(private val sourceText: String) : TomlParserBaseListener() {
    val versions = mutableMapOf<String, CatalogVersion>()
    val libraries = mutableMapOf<String, CatalogLibrarys>()
    val plugins = mutableMapOf<String, CatalogPlugin>()

    // 当前正在处理的表名称，例如 "versions", "libraries"
    private var currentSection: String = ""

    // 进入标准表头：[xxx]
    override fun enterStandard_table(ctx: TomlParser.Standard_tableContext) {
      // key() 可能包含引号，需要清理
      val rawKey = ctx.key().text
      currentSection = cleanKey(rawKey)
    }

    // 键值对：key = value
    override fun exitKey_value(ctx: TomlParser.Key_valueContext) {
      val keyContext = ctx.key()
      val valueContext = ctx.value()

      val key = cleanKey(keyContext.text)

      when (currentSection) {
        "versions" -> parseVersionEntry(key, valueContext)
        "libraries" -> parseLibraryEntry(key, valueContext)
        "plugins" -> parsePluginEntry(key, valueContext)
      }
    }

    private fun parseVersionEntry(key: String, valueCtx: TomlParser.ValueContext) {
      // versions 块下，value 必须是字符串
      val stringCtx = valueCtx.string()
      if (stringCtx != null) {
        val rawText = stringCtx.text
        val cleanValue = cleanString(rawText)
        // 计算 cleanValue 在 rawText 中的偏移
        // ANTLR 的 range 是 inclusive 的，TextRange 是 end exclusive 的
        // 假设是基础字符串 "1.0.0"，起始+1，结束-1
        val start = stringCtx.start.startIndex
        val range = calculateStringContentRange(start, rawText, cleanValue)

        versions[key] = CatalogVersion(key, cleanValue, range)
      }
    }

    private fun parseLibraryEntry(alias: String, valueCtx: TomlParser.ValueContext) {
      // 情况 1: 字符串形式 "group:name:version"
      val stringCtx = valueCtx.string()
      if (stringCtx != null) {
        val rawText = stringCtx.text
        val cleanContent = cleanString(rawText)
        val parts = cleanContent.split(":")

        if (parts.size >= 3) {
          val group = parts[0]
          val name = parts[1]
          val version = parts[2]

          // 计算版本号在字符串中的位置
          val versionIndex = rawText.lastIndexOf(version)
          if (versionIndex != -1) {
            val absStart = stringCtx.start.startIndex + versionIndex
            val range = TextRange(absStart, absStart + version.length)
            libraries[alias] = CatalogLibrarys(alias, group, name, null, version, range)
          }
        }
        return
      }

      // 情况 2: 内联表形式 { group = "...", name = "...", version = "..." }
      val inlineTableCtx = valueCtx.inline_table()
      if (inlineTableCtx != null) {
        var group: String? = null
        var name: String? = null
        var versionLiteral: String? = null
        var versionRef: String? = null
        var verRange: TextRange? = null

        // 遍历内联表中的键值对
        // inline_table -> inline_table_keyvals -> inline_table_keyvals_non_empty -> key = value,
        // ...
        val keyvalsCtx = inlineTableCtx.inline_table_keyvals()?.inline_table_keyvals_non_empty()

        if (keyvalsCtx != null) {
          // keyvalsCtx 是递归结构，需要手动展开或者利用 ANTLR 访问器，
          // 这里为了简单，我们直接遍历其子节点或重新解析
          // 由于 ANTLR 生成的 Context 结构比较深，我们通过遍历所有 KeyValue 子节点来处理

          // 收集所有属性
          val properties = extractInlineProperties(keyvalsCtx)

          group = properties["group"]?.first
          name = properties["name"]?.first
          val module = properties["module"]?.first

          // 处理 module 简写
          if (module != null && group == null) {
            val parts = module.split(":")
            if (parts.size >= 2) {
              group = parts[0]
              name = parts[1]
            }
          }

          val verPair = properties["version"]
          if (verPair != null) {
            versionLiteral = verPair.first
            verPair.second?.let { tokenRange ->
              // 重新计算纯版本号的 Range (去引号)
              verRange =
                  calculateStringContentRange(
                      tokenRange.startOffset,
                      "\"$versionLiteral\"",
                      versionLiteral!!,
                  )
            }
          }

          versionRef = properties["version.ref"]?.first
        }

        if (group != null && name != null) {
          libraries[alias] =
              CatalogLibrarys(
                  alias = alias,
                  group = group,
                  name = name,
                  versionRef = versionRef,
                  versionLiteral = versionLiteral,
                  textRange = verRange ?: TextRange(0, 0),
              )
        }
      }
    }

    private fun parsePluginEntry(alias: String, valueCtx: TomlParser.ValueContext) {
      val inlineTableCtx = valueCtx.inline_table()
      if (inlineTableCtx != null) {
        val keyvalsCtx = inlineTableCtx.inline_table_keyvals()?.inline_table_keyvals_non_empty()
        if (keyvalsCtx != null) {
          val properties = extractInlineProperties(keyvalsCtx)

          val id = properties["id"]?.first
          val versionRef = properties["version.ref"]?.first
          val versionLiteral = properties["version"]?.first
          val versionRange = properties["version"]?.second

          if (id != null) {
            plugins[alias] =
                CatalogPlugin(
                    alias = alias,
                    id = id,
                    versionRef = versionRef,
                    versionLiteral = versionLiteral,
                    textRange = versionRange,
                )
          }
        }
      }
    }

    // --- 辅助方法 ---

    /** 提取内联表中的所有属性。 返回 Map<Key, Pair<CleanValue, RawTokenRange>> */
    private fun extractInlineProperties(
        ctx: TomlParser.Inline_table_keyvals_non_emptyContext
    ): Map<String, Pair<String, TextRange>> {
      val result = mutableMapOf<String, Pair<String, TextRange>>()

      // 这是一个递归结构: key = value, [inline_table_keyvals_non_empty]
      var current: TomlParser.Inline_table_keyvals_non_emptyContext? = ctx
      while (current != null) {
        val key = cleanKey(current.key().text)
        val valCtx = current.value()
        val stringCtx = valCtx.string()

        if (stringCtx != null) {
          val raw = stringCtx.text
          val clean = cleanString(raw)
          val range = TextRange(stringCtx.start.startIndex, stringCtx.stop.stopIndex + 1)
          result[key] = Pair(clean, range)
        } else if (valCtx.inline_table() != null) {
          collectNestedInlineProperties(key, valCtx.inline_table(), result)
        }

        current = current.inline_table_keyvals_non_empty()
      }
      return result
    }

    private fun collectNestedInlineProperties(
        prefix: String,
        inlineTableCtx: TomlParser.Inline_tableContext,
        out: MutableMap<String, Pair<String, TextRange>>,
    ) {
      var current = inlineTableCtx.inline_table_keyvals()?.inline_table_keyvals_non_empty()
      while (current != null) {
        val key = cleanKey(current.key().text)
        val combinedKey = "$prefix.$key"
        val valueCtx = current.value()
        val stringCtx = valueCtx.string()

        if (stringCtx != null) {
          val raw = stringCtx.text
          val clean = cleanString(raw)
          out[combinedKey] = Pair(clean, TextRange(stringCtx.start.startIndex, stringCtx.stop.stopIndex + 1))
        } else if (valueCtx.inline_table() != null) {
          collectNestedInlineProperties(combinedKey, valueCtx.inline_table(), out)
        }

        current = current.inline_table_keyvals_non_empty()
      }
    }

    private fun cleanKey(key: String): String {
      return key.trim().replace("\"", "").replace("'", "")
    }

    private fun cleanString(str: String): String {
      if (str.startsWith("\"\"\"") || str.startsWith("'''")) {
        return str.substring(3, str.length - 3)
      }
      if (str.startsWith("\"") || str.startsWith("'")) {
        return str.substring(1, str.length - 1)
      }
      return str
    }

    private fun calculateStringContentRange(
        tokenStartOffset: Int,
        rawText: String,
        cleanContent: String,
    ): TextRange {
      val contentStart = rawText.indexOf(cleanContent)
      if (contentStart != -1) {
        val absStart = tokenStartOffset + contentStart
        return TextRange(absStart, absStart + cleanContent.length)
      }
      return TextRange(tokenStartOffset, tokenStartOffset + rawText.length)
    }
  }
}
