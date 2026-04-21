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

package com.itsaky.androidide.lsp.kotlin.providers

import com.itsaky.androidide.lsp.api.AbstractServiceProvider
import com.itsaky.androidide.lsp.api.ICompletionProvider
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServerImpl
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.utils.Logger
import java.util.Locale

/** @author android_zero */
class KotlinCompletionProvider : AbstractServiceProvider(), ICompletionProvider {

  companion object {
    private val log = Logger.instance("KotlinCompletionProvider")
  }

  override fun canComplete(file: java.nio.file.Path?): Boolean {
    if (file == null) return false
    val pathStr = file.toString()
    return super.canComplete(file) &&
        (pathStr.endsWith(".kt", true) || pathStr.endsWith(".kts", true))
  }

  override fun complete(params: CompletionParams): CompletionResult {
    abortCompletionIfCancelled()

    if (!settings.completionsEnabled()) {
      return CompletionResult.EMPTY
    }

    try {
      val server =
          ILanguageServerRegistry.getDefault().getServer("kotlin-lsp") as? KotlinLanguageServerImpl
              ?: return CompletionResult.EMPTY

      val result = server.complete(params)
      val prefix = params.prefix ?: ""

      if (prefix.isNotEmpty()) {
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        val filtered =
            result.items
                .map { item ->
                  val insert = item.insertText
                  val label = item.ideLabel
                  val detail = item.detail ?: ""
                  item.matchLevel =
                      when {
                        insert.startsWith(prefix, ignoreCase = true) ||
                            label.startsWith(prefix, ignoreCase = true) ->
                            MatchLevel.CASE_INSENSITIVE_PREFIX
                        insert.contains(prefix, ignoreCase = true) ||
                            label.contains(prefix, ignoreCase = true) ->
                            MatchLevel.PARTIAL_MATCH
                        detail.lowercase(Locale.ROOT).contains(normalizedPrefix) ->
                            MatchLevel.PARTIAL_MATCH
                        else -> MatchLevel.NO_MATCH
                      }
                  item
                }
                .filter { it.matchLevel != MatchLevel.NO_MATCH }

        return CompletionResult(filtered)
      }

      return result
    } catch (e: Exception) {
      log.error("Exception occurred during Kotlin completion resolution", e)
      return CompletionResult.EMPTY
    }
  }
}
