package com.aicode.helper.completion

import com.aicode.helper.service.AiApiService
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange

class AiCompletionContributor : CompletionContributor() {

    private val log = Logger.getInstance(AiCompletionContributor::class.java)

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val settings = AiCodeSettings.getInstance()
        if (settings.apiKey.isBlank()) return

        val editor = parameters.editor
        val document = editor.document
        val offset = parameters.offset

        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val currentLine = document.getText(
            TextRange(lineStart, offset)
        ).trim()

        if (currentLine.length < 3) return

        val startLine = maxOf(0, lineNumber - 20)
        val contextStart = document.getLineStartOffset(startLine)
        val contextCode = document.getText(
            TextRange(contextStart, offset)
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = AiApiService()
                val messages = listOf(
                    AiApiService.Message(
                        "system",
                        "你是一个代码补全助手。根据用户提供的代码上下文，给出1-3个最可能的代码补全建议。" +
                                "每个建议只包含当前行的剩余部分（不需要重复已有内容），用换行符分隔。" +
                                "只输出代码补全内容，不要解释，不要使用代码块标记。"
                    ),
                    AiApiService.Message(
                        "user",
                        "代码上下文：\n```\n$contextCode\n```\n\n请给出当前行的代码补全建议（只输出补全内容）："
                    )
                )
                val completionText = apiService.chat(messages)
                val suggestions = completionText.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(3)

                ApplicationManager.getApplication().invokeLater {
                    suggestions.forEachIndexed { index, suggestion ->
                        val element = LookupElementBuilder.create(suggestion)
                            .withIcon(AllIcons.Actions.IntentionBulb)
                            .withTypeText("AI 补全")
                            .withBoldness(true)
                        result.addElement(
                            PrioritizedLookupElement.withPriority(element, (100 - index).toDouble())
                        )
                    }
                }
            } catch (e: Exception) {
                log.warn("AI 代码补全失败: ${e.message}")
            }
        }
    }
}
