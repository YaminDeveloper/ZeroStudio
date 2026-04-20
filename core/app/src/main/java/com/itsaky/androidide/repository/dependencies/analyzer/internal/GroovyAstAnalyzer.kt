/*
 * @author android_zero
 * 包名：com.itsaky.androidide.repository.dependencies.analyzer.internal
 * 用途：针对 build.gradle (Groovy) 的高精度词法分析器。
 * 依赖：com.itsaky.androidide.lexers.groovy.GroovyLexer
 */
package com.itsaky.androidide.repository.dependencies.analyzer.internal

import com.itsaky.androidide.lexers.groovy.GroovyLexer
import com.itsaky.androidide.repository.dependencies.models.datas.*
import com.itsaky.androidide.repository.dependencies.models.enums.*
import com.itsaky.androidide.repository.dependencies.models.interfaces.*
import java.io.File
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Token

class GroovyAstAnalyzer : ScriptAnalyzer {

  override fun analyze(file: File): Pair<List<ScopedRepositoryInfo>, List<ScopedDependencyInfo>> {
    val repos = mutableListOf<ScopedRepositoryInfo>()
    val deps = mutableListOf<ScopedDependencyInfo>()

    if (!file.exists()) return Pair(repos, deps)

    val text = file.readText()
    val versionVariables = extractVersionVariables(text)
    val lexer = GroovyLexer(CharStreams.fromString(text))

    // 获取所有 Token，并过滤掉无意义的空白符
    val validTokens =
        lexer.allTokens.filter {
          it.type != GroovyLexer.WS &&
              it.type != GroovyLexer.COMMENT &&
              it.type != GroovyLexer.LINE_COMMENT
        }

    var depth = 0
    var blockDepth = -1
    var currentBlock: String? = null

    var i = 0
    while (i < validTokens.size) {
      val token = validTokens[i]

      //  维护闭包深度状态机
      if (token.type == GroovyLexer.LBRACE) {
        depth++
      } else if (token.type == GroovyLexer.RBRACE) {
        depth--
        // 如果回到了进入当前块之前的深度，说明已经退出了该块
        if (depth == blockDepth) {
          currentBlock = null
          blockDepth = -1
        }
      }

      // 探测闭包入口
      // 模式： identifier {
      if (
          token.type == GroovyLexer.IDENTIFIER &&
              i + 1 < validTokens.size &&
              validTokens[i + 1].type == GroovyLexer.LBRACE
      ) {
        val blockName = token.text
        if (
            blockName == "dependencies" ||
                blockName == "repositories" ||
                blockName == "pluginManagement"
        ) {
          currentBlock = blockName
          blockDepth = depth // 记录进入前的深度
        }
      }

      // ------------- 解析 Dependencies 块 -------------
      if (currentBlock == "dependencies") {
        // 常见的配置名称：implementation, api, ksp 等，它们在 Lexer 中通常被识别为 IDENTIFIER
        if (token.type == GroovyLexer.IDENTIFIER) {
          val configName = token.text
          if (!DependencyDslConfigurations.isDependencyConfiguration(configName)) {
            continue
          }

          if (i + 1 < validTokens.size) {
            val next = validTokens[i + 1]
            var gavToken: Token? = null
            var isToml = false
            var tomlRefStr = ""

            // 模式 1: implementation 'g:a:v'  (无括号)
            if (
                next.type == GroovyLexer.STRING_LITERAL ||
                    next.type == GroovyLexer.SINGLE_QUOTE_STRING
            ) {
              gavToken = next
            }
            // 模式 2: implementation("g:a:v") (有括号)
            else if (next.type == GroovyLexer.LPAREN && i + 2 < validTokens.size) {
              val next2 = validTokens[i + 2]

              // implementation("g:a:v")
              if (
                  next2.type == GroovyLexer.STRING_LITERAL ||
                      next2.type == GroovyLexer.SINGLE_QUOTE_STRING
              ) {
                gavToken = next2
              }
              // 模式 3: implementation(libs.xxx) (TOML 引用)
              else if (next2.type == GroovyLexer.IDENTIFIER && next2.text == "libs") {
                isToml = true
                var j = i + 2
                // 向后扫描，拼接 libs.xxx.yyy 引用链
                // 只要是 DOT 或 IDENTIFIER 就认为是引用链的一部分
                while (
                    j < validTokens.size &&
                        (validTokens[j].type == GroovyLexer.DOT ||
                            validTokens[j].type == GroovyLexer.IDENTIFIER)
                ) {
                  tomlRefStr += validTokens[j].text
                  j++
                }

                // 添加 TOML 引用依赖
                deps.add(
                    ScopedDependencyInfo(
                        configuration = configName,
                        groupId = "placeholder",
                        artifactId = "placeholder",
                        version = "placeholder",
                        declaredFile = file,
                        declarationType = DeclarationType.CATALOG_ACCESSOR,
                        versionReference = tomlRefStr.removePrefix("libs."), // 去掉 libs. 前缀
                        // 记录整条语句的 Range，用于可能的整行删除/替换
                        statementTextRange =
                            TextRange(token.startIndex, validTokens[j - 1].stopIndex + 1),
                    )
                )

                // 更新主循环索引，跳过已处理的 token
                i = j - 1
                continue
              }
            }

            // 处理字符串形式的依赖
            if (!isToml && gavToken != null) {
              val rawStr = gavToken.text
              // 去除首尾引号
              val cleanStr = rawStr.trim('\'', '\"')
              val parts = cleanStr.split(":")

              if (parts.size >= 3) {
                val rawVersion = parts[2]
                val variableName =
                    Regex("""^\$(\w+)$|^\$\{(\w+)}$""")
                        .matchEntire(rawVersion)
                        ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                val variable = variableName?.let { versionVariables[it] }
                val version = variable?.first ?: rawVersion
                val versionRange =
                    variable?.second
                        ?: run {
                          val verStart = gavToken.startIndex + rawStr.lastIndexOf(rawVersion)
                          TextRange(verStart, verStart + rawVersion.length)
                        }

                deps.add(
                    ScopedDependencyInfo(
                        configuration = configName,
                        groupId = parts[0],
                        artifactId = parts[1],
                        version = version,
                        declaredFile = file,
                        declarationType = DeclarationType.STRING_LITERAL,
                        // 精确记录版本号的位置 Range
                        versionDefinitionRange = versionRange,
                        statementTextRange = TextRange(token.startIndex, gavToken.stopIndex + 1),
                    )
                )
              }
            }
          }
        }
      }
      // ------------- 解析 Repositories / pluginManagement 块 -------------
      else if (currentBlock == "repositories" || currentBlock == "pluginManagement") {
        if (token.type == GroovyLexer.IDENTIFIER) {
          val funcName = token.text

          if (funcName == "google") {
            repos.add(
                ScopedRepositoryInfo(
                    "google",
                    "https://maven.google.com/",
                    RepositoryType.GOOGLE,
                    file,
                )
            )
          } else if (funcName == "mavenCentral") {
            repos.add(
                ScopedRepositoryInfo(
                    "mavenCentral",
                    "https://repo1.maven.org/maven2/",
                    RepositoryType.MAVEN_CENTRAL,
                    file,
                )
            )
          } else if (funcName == "maven") {
            // maven { url '...' }
            // 向前搜索匹配 url '...'，直到遇到 RBRACE (}) 结束
            var j = i + 1
            while (j < validTokens.size && validTokens[j].type != GroovyLexer.RBRACE) {
              if (validTokens[j].type == GroovyLexer.IDENTIFIER && validTokens[j].text == "url") {
                // 找到 url 关键字，继续找后面的字符串
                var k = j + 1
                while (k < validTokens.size && validTokens[k].type != GroovyLexer.RBRACE) {
                  val tk = validTokens[k]
                  // 找到 URL 字符串
                  if (
                      tk.type == GroovyLexer.STRING_LITERAL ||
                          tk.type == GroovyLexer.SINGLE_QUOTE_STRING
                  ) {
                    val url = tk.text.trim('\'', '\"')
                    if (url.startsWith("http")) {
                      repos.add(
                          ScopedRepositoryInfo(
                              "custom_${url.hashCode()}",
                              url,
                              RepositoryType.CUSTOM_MAVEN,
                              file,
                          )
                      )
                    }
                    break // 找到 url 后跳出内层循环
                  }
                  k++
                }
                break // 找到 url 关键字处理完后跳出中层循环
              }
              j++
            }
          }
        }
      }
      i++
    }
    return Pair(repos, deps)
  }

  private fun extractVersionVariables(text: String): Map<String, Pair<String, TextRange>> {
    val result = mutableMapOf<String, Pair<String, TextRange>>()
    val pattern =
        Regex(
            """(?m)^\s*(?:def\s+|final\s+|ext\.)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*['"]([^'"]+)['"]\s*$"""
        )
    pattern.findAll(text).forEach { match ->
      val name = match.groupValues[1]
      val value = match.groupValues[2]
      val start = match.range.first + match.value.indexOf(value)
      result[name] = value to TextRange(start, start + value.length)
    }
    return result
  }
}
