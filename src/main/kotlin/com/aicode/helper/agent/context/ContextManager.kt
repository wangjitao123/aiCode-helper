package com.aicode.helper.agent.context

import com.aicode.helper.agent.AgentMessage
import com.aicode.helper.agent.AgentState
import com.aicode.helper.agent.Role
import com.aicode.helper.agent.event.AgentEvent
import com.aicode.helper.agent.hooks.CompactionLayer
import com.aicode.helper.agent.hooks.HookManager
import com.aicode.helper.service.LlmClient
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.openapi.diagnostic.Logger

/**
 * 决策 #3：五层压缩策略的上下文管理。
 *
 * 从轻到重依次触发：
 *   L1 microcompact     —— 每轮无条件，仅折叠重复/超大的旧工具结果（代价极低）
 *   L2 snip             —— 历史过长时截断中间段落（代价低）
 *   L3 autocompact      —— 接近阈值（contextWindow - 13k buffer）时调用摘要模型（代价中）
 *   L4 reactiveCompact  —— API 返回 413 后主动压缩再重试（代价高）
 *   L5 contextCollapse  —— 413 后先于 reactiveCompact，先消耗已暂存的折叠（代价中）
 *
 * 每个环节是可独立组合的“generator 段”：preprocess() 串起 L1/L2/L3，
 * reactiveCompact() 串起 L5/L4，由 QueryEngine 主循环用 emit 把事件流出。
 */
class ContextManager(
    private val llm: LlmClient,
    private val hooks: HookManager
) {
    private val log = Logger.getInstance(ContextManager::class.java)

    /** 每轮开始时的上下文预处理（L1 → L2 → L3）。 */
    suspend fun preprocess(state: AgentState, emit: suspend (AgentEvent) -> Unit) {
        val settings = AiCodeSettings.getInstance()

        // L1 microcompact —— 无条件
        if (microcompact(state)) {
            hooks.firePreCompact(CompactionLayer.MICROCOMPACT, state)
            emit(AgentEvent.Compaction("microcompact", "折叠了较早的重复/超大工具结果"))
            hooks.firePostCompact(CompactionLayer.MICROCOMPACT, state)
        }

        // L2 snip —— 历史过长
        if (state.messages.size > CompactionConstants.SNIP_MESSAGE_THRESHOLD) {
            hooks.firePreCompact(CompactionLayer.SNIP, state)
            val removed = compactMiddle(state, CompactionConstants.SNIP_KEEP_RECENT, summarize = false)
            if (removed > 0) emit(AgentEvent.Compaction("snip", "截断了中间 $removed 条消息"))
            hooks.firePostCompact(CompactionLayer.SNIP, state)
        }

        // L3 autocompact —— 接近 token 阈值
        if (settings.enableAutocompact) {
            val tokens = TokenEstimator.estimate(state.messages)
            val threshold = settings.contextWindowTokens - CompactionConstants.AUTOCOMPACT_BUFFER_TOKENS
            if (tokens > threshold) {
                hooks.firePreCompact(CompactionLayer.AUTOCOMPACT, state)
                emit(AgentEvent.Compaction("autocompact", "估算 $tokens tokens 接近阈值 $threshold，调用摘要模型压缩"))
                val removed = compactMiddle(state, CompactionConstants.SNIP_KEEP_RECENT, summarize = true)
                emit(AgentEvent.Compaction("autocompact", "已将中间 $removed 条消息压缩为摘要"))
                hooks.firePostCompact(CompactionLayer.AUTOCOMPACT, state)
            }
        }
    }

    /** 413 之后的主动压缩：L5 contextCollapse → L4 reactiveCompact。 */
    suspend fun reactiveCompact(state: AgentState, emit: suspend (AgentEvent) -> Unit) {
        // L5 contextCollapse —— 先消耗“已暂存的折叠”：把最大的工具结果直接截断，代价低于全量摘要
        hooks.firePreCompact(CompactionLayer.CONTEXT_COLLAPSE, state)
        val collapsed = contextCollapse(state)
        if (collapsed > 0) emit(AgentEvent.Compaction("contextCollapse", "折叠了 $collapsed 条超大工具结果"))
        hooks.firePostCompact(CompactionLayer.CONTEXT_COLLAPSE, state)

        // L4 reactiveCompact —— 再做一次激进的全量摘要
        hooks.firePreCompact(CompactionLayer.REACTIVE_COMPACT, state)
        val removed = compactMiddle(state, keepRecent = 6, summarize = true)
        emit(AgentEvent.Compaction("reactiveCompact", "413 后主动压缩，将中间 $removed 条消息压缩为摘要"))
        hooks.firePostCompact(CompactionLayer.REACTIVE_COMPACT, state)
    }

    // ---- L1 ----
    private fun microcompact(state: AgentState): Boolean {
        val messages = state.messages
        val toolIndices = messages.indices.filter { messages[it].role == Role.TOOL }
        if (toolIndices.size <= CompactionConstants.MICROCOMPACT_RECENT_KEPT) return false

        // 最近若干条工具结果保持原样
        val keepFrom = toolIndices.takeLast(CompactionConstants.MICROCOMPACT_RECENT_KEPT).first()
        var changed = false
        val seen = HashSet<String>()

        for (i in toolIndices) {
            if (i >= keepFrom) continue
            val msg = messages[i]
            val isLarge = msg.content.length > CompactionConstants.MICROCOMPACT_LARGE_RESULT_CHARS
            val isDuplicate = !seen.add(msg.content)
            if (msg.content.startsWith("[已折叠")) continue
            if (isLarge || isDuplicate) {
                val kept = msg.content.take(CompactionConstants.MICROCOMPACT_FOLD_KEEP_CHARS)
                messages[i] = msg.copy(
                    content = "[已折叠的较早工具结果，原长 ${msg.content.length} 字符]\n$kept ..."
                )
                changed = true
            }
        }
        return changed
    }

    // ---- L5 ----
    private fun contextCollapse(state: AgentState): Int {
        val messages = state.messages
        var collapsed = 0
        // 找出最大的若干条工具结果直接截断
        val largest = messages.indices
            .filter { messages[it].role == Role.TOOL && messages[it].content.length > CompactionConstants.MICROCOMPACT_LARGE_RESULT_CHARS }
            .sortedByDescending { messages[it].content.length }
            .take(5)
        for (i in largest) {
            val msg = messages[i]
            if (msg.content.startsWith("[已折叠")) continue
            messages[i] = msg.copy(
                content = "[已折叠的工具结果，原长 ${msg.content.length} 字符]\n" +
                        msg.content.take(CompactionConstants.MICROCOMPACT_FOLD_KEEP_CHARS) + " ..."
            )
            collapsed++
        }
        return collapsed
    }

    // ---- L2 / L3 / L4 共用：压缩“中间段落” ----
    private fun compactMiddle(state: AgentState, keepRecent: Int, summarize: Boolean): Int {
        val messages = state.messages

        // 受保护的头部：开头连续的 SYSTEM + 第一条 USER
        var headEnd = 0
        while (headEnd < messages.size && messages[headEnd].role == Role.SYSTEM) headEnd++
        if (headEnd < messages.size && messages[headEnd].role == Role.USER) headEnd++

        // 受保护的尾部起点；为避免出现“孤儿 tool 结果”，向后推进直到不是 TOOL 消息
        var tailStart = (messages.size - keepRecent).coerceAtLeast(headEnd)
        while (tailStart < messages.size && messages[tailStart].role == Role.TOOL) tailStart++

        if (tailStart <= headEnd) return 0
        val middle = messages.subList(headEnd, tailStart).toList()
        if (middle.isEmpty()) return 0

        val replacement: AgentMessage = if (summarize) {
            val blob = middle.joinToString("\n\n") { m ->
                val tools = if (m.toolCalls.isNotEmpty())
                    " [调用工具: ${m.toolCalls.joinToString { it.name }}]" else ""
                "${m.role.api}$tools: ${m.content.take(4000)}"
            }
            val summary = try {
                llm.summarize(
                    "你是上下文压缩助手。请把下面这段 agent 与工具交互的历史，压缩成简洁但保留关键事实、" +
                            "已读取的文件要点、已得出的结论的中文摘要，供后续对话继续使用。",
                    blob
                )
            } catch (e: Exception) {
                log.warn("摘要压缩失败，退化为截断: ${e.message}")
                middle.joinToString("\n") { it.content.take(200) }
            }
            AgentMessage(Role.SYSTEM, "（对话历史摘要，已压缩 ${middle.size} 条消息）\n$summary")
        } else {
            AgentMessage(Role.SYSTEM, "[... 省略中间 ${middle.size} 条探索性消息 ...]")
        }

        val newList = ArrayList<AgentMessage>(messages.size - middle.size + 1)
        newList.addAll(messages.subList(0, headEnd))
        newList.add(replacement)
        newList.addAll(messages.subList(tailStart, messages.size))
        messages.clear()
        messages.addAll(newList)
        return middle.size
    }
}

