package com.aicode.helper.agent.context

import com.aicode.helper.agent.AgentMessage

/**
 * 启发式 token 估算。我们没有真实分词器，用 字符数 / 4 的经验比例近似，
 * 并为每条消息加上少量结构开销。阈值因此都是近似值（已在常量注释中说明）。
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4
    private const val PER_MESSAGE_OVERHEAD = 4

    fun estimate(text: String): Int = text.length / CHARS_PER_TOKEN + PER_MESSAGE_OVERHEAD

    fun estimate(message: AgentMessage): Int {
        var tokens = estimate(message.content)
        for (tc in message.toolCalls) {
            tokens += estimate(tc.name) + estimate(tc.argumentsJson)
        }
        return tokens
    }

    fun estimate(messages: List<AgentMessage>): Int = messages.sumOf { estimate(it) }
}

