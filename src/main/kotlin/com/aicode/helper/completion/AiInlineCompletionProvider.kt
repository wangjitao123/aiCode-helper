package com.aicode.helper.completion

import com.aicode.helper.service.AiApiService
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.channelFlow

class AiInlineCompletionProvider : InlineCompletionProvider {

    private val log = Logger.getInstance(AiInlineCompletionProvider::class.java)

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("AiCodeHelperInline")

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSingleSuggestion {
        return InlineCompletionSingleSuggestion.build {
            val settings = AiCodeSettings.getInstance()
            if (settings.apiKey.isBlank()) return@build

            val editor = request.editor
            val document = request.document
            val offset = request.endOffset

            val lineNumber = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineNumber)
            val currentLine = document.getText(TextRange(lineStart, offset)).trim()

            if (currentLine.length < 2) return@build

            val startLine = maxOf(0, lineNumber - 30)
            val contextStart = document.getLineStartOffset(startLine)
            val contextCode = document.getText(TextRange(contextStart, offset))

            // Also get some code after cursor for better context
            val endLine = minOf(document.lineCount - 1, lineNumber + 10)
            val contextEnd = document.getLineEndOffset(endLine)
            val afterCode = if (offset < contextEnd) document.getText(TextRange(offset, contextEnd)) else ""

            try {
                val completionText = withContext(Dispatchers.IO) {
                    val apiService = AiApiService()
                    val messages = listOf(
                        AiApiService.Message(
                            "system",
                            "你是一个代码补全助手。根据用户提供的代码上下文和光标位置，直接输出光标处最可能的代码补全内容。" +
                                    "只输出需要插入的代码，不要重复已有内容，不要解释，不要使用代码块标记（```），不要添加任何前缀或说明。" +
                                    "如果无法补全则输出空。补全内容应该简洁实用，可以是一行或多行。"
                        ),
                        AiApiService.Message(
                            "user",
                            "光标前的代码：\n```\n$contextCode\n```\n\n光标后的代码：\n```\n$afterCode\n```\n\n请直接输出光标处的代码补全："
                        )
                    )
                    apiService.chat(messages)
                }

                val suggestion = completionText
                    .trimEnd()
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                if (suggestion.isNotBlank()) {
                    emit(InlineCompletionGrayTextElement(suggestion))
                }
            } catch (e: Exception) {
                log.warn("AI inline completion failed: ${e.message}")
            }
        }
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = AiCodeSettings.getInstance()
        return settings.apiKey.isNotBlank()
    }
}
