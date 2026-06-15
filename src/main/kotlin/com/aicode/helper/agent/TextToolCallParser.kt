package com.aicode.helper.agent

import com.aicode.helper.agent.tools.ToolRegistry
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 文本协议工具调用解析（兼容性回退）。
 *
 * 背景：并不是所有模型都走 OpenAI 原生 function-calling（`delta.tool_calls`）。
 * 很多模型（DeepSeek、本地 Ollama、各类开源模型）会把工具调用**以文本形式**写在
 * 回答里，例如：
 *   - `<tool_call>{"name":"read_file","arguments":{"path":"build.gradle.kts"}}</tool_call>`
 *   - `<|tool_call>call:project_structure{}<tool_call|>`（特殊 token 风格）
 *   - ```json\n{"name":"grep_search","arguments":{"query":"LlmClient"}}\n```
 *
 * 这些都不会出现在 `delta.tool_calls` 里，于是 [QueryEngine] 会误以为“没有工具调用”
 * 而直接结束。本解析器把这些文本形式的调用解析出来，让 ReAct 主循环得以继续。
 *
 * 安全策略：只有当解析出的工具名在 [ToolRegistry] 中真实存在时，才认定为工具调用，
 * 避免把普通 JSON 文本误判为调用。
 */
object TextToolCallParser {

    private val gson = Gson()

    /** XML / 特殊 token 风格：<tool_call> ... </tool_call> 及其变体。 */
    private val xmlBlock = Regex(
        """<\s*[|｜]?\s*(?:tool_call|tool▁call|function_call|tool_use)\s*[|｜]?\s*>(.*?)<\s*/?\s*[|｜]?\s*(?:tool_call|tool▁call|function_call|tool_use)\s*[|｜]?\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Markdown 代码围栏：```tool_call / ```json ... ``` 。 */
    private val fencedBlock = Regex(
        """```(?:tool_call|tool_code|tools?|json)?[ \t]*\r?\n?(.*?)```""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 块内简写：`call:NAME{...}` 或 `NAME{...}`。 */
    private val callShorthand = Regex(
        """(?:call\s*[:：]\s*)?([A-Za-z_][\w-]*)\s*(\{.*})?""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * 从助手文本中解析工具调用。返回空列表表示这是一段普通回答（无工具调用）。
     */
    fun parse(text: String, registry: ToolRegistry): List<ToolCall> {
        if (text.isBlank()) return emptyList()
        val calls = ArrayList<ToolCall>()
        val seen = HashSet<String>()

        fun add(name: String, argsJson: String) {
            if (registry.find(name) == null) return
            val normalized = normalizeArgs(argsJson)
            val key = "$name|$normalized"
            if (seen.add(key)) calls.add(ToolCall("text_call_${calls.size}", name, normalized))
        }

        fun addFromJson(obj: JsonObject) {
            val fn = obj.getAsJsonObject("function")
            val name = primitiveString(obj.get("name"))
                ?: primitiveString(obj.get("tool"))
                ?: primitiveString(fn?.get("name"))
                ?: return
            val argsEl = obj.get("arguments") ?: obj.get("parameters") ?: obj.get("args")
                ?: obj.get("input") ?: fn?.get("arguments")
            val argsJson = when {
                argsEl == null -> "{}"
                argsEl.isJsonObject -> argsEl.toString()
                argsEl.isJsonPrimitive && argsEl.asJsonPrimitive.isString -> argsEl.asString.ifBlank { "{}" }
                else -> "{}"
            }
            add(name, argsJson)
        }

        fun tryJson(raw: String) {
            val t = raw.trim()
            if (t.isEmpty()) return
            try {
                val el = gson.fromJson(t, JsonElement::class.java) ?: return
                when {
                    el.isJsonObject -> addFromJson(el.asJsonObject)
                    el.isJsonArray -> el.asJsonArray.forEach { if (it.isJsonObject) addFromJson(it.asJsonObject) }
                    else -> {}
                }
            } catch (_: Exception) { /* 不是 JSON，忽略 */ }
        }

        // 1) XML / 特殊 token 风格块
        for (m in xmlBlock.findAll(text)) {
            val inner = m.groupValues[1].trim()
            val before = calls.size
            tryJson(inner)
            if (calls.size == before) {
                // 退化为 call:NAME{...} 简写
                callShorthand.find(inner)?.let { cm ->
                    val name = cm.groupValues[1]
                    val args = cm.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "{}"
                    add(name, args)
                }
            }
        }

        // 2) 代码围栏块（仅当包含 name 字段时尝试，避免误吃普通代码块）
        if (calls.isEmpty()) {
            for (m in fencedBlock.findAll(text)) {
                val inner = m.groupValues[1]
                if (inner.contains("\"name\"") || inner.contains("'name'")) tryJson(inner)
            }
        }

        // 3) 整段就是 JSON（对象或数组）
        if (calls.isEmpty()) {
            val t = text.trim()
            val looksJson = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
            if (looksJson && t.contains("\"name\"")) tryJson(t)
        }

        return calls
    }

    /** 去掉文本里的工具调用标记，得到适合入历史/展示的“干净”文本。 */
    fun strip(text: String): String {
        var out = xmlBlock.replace(text, "")
        out = fencedBlock.replace(out) { m -> if (m.groupValues[1].contains("\"name\"")) "" else m.value }
        return out.trim()
    }

    private fun primitiveString(el: JsonElement?): String? =
        el?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun normalizeArgs(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return "{}"
        return try {
            if (gson.fromJson(t, JsonObject::class.java) != null) t else "{}"
        } catch (_: Exception) {
            "{}"
        }
    }
}

/**
 * 流式文本过滤器：在模型逐 token 输出时，把“文本协议工具调用”的标记从展示中隐藏，
 * 使聊天气泡保持干净（接近 Claude Code 的观感）。完整文本仍由调用方单独累积用于解析。
 *
 * 一旦检测到工具调用起始标记，就抑制其后所有文本的展示（这类协议通常在一条消息里
 * “要么说话、要么调用”，调用之后的内容交给主循环处理）。
 */
class ToolCallStreamFilter {

    private val markers = listOf(
        "<tool_call", "<|tool_call", "<｜tool_call", "<tool▁call",
        "<function_call", "<tool_use"
    )
    private val maxMarker = markers.maxOf { it.length }
    private val buf = StringBuilder()
    private var suppressed = false

    /** 喂入一段增量，返回此刻可安全展示的文本。 */
    fun feed(delta: String): String {
        if (suppressed) return ""
        buf.append(delta)
        val s = buf.toString()

        // 出现完整起始标记：展示其之前的部分，之后全部抑制
        var idx = -1
        for (mk in markers) {
            val i = s.indexOf(mk, ignoreCase = true)
            if (i >= 0 && (idx == -1 || i < idx)) idx = i
        }
        if (idx >= 0) {
            suppressed = true
            val safe = s.substring(0, idx)
            buf.setLength(0)
            return safe
        }

        // 未出现完整标记：保留末尾可能是“标记前缀”的字符，其余安全展示
        val keep = partialPrefixLen(s)
        val emit = s.substring(0, s.length - keep)
        buf.setLength(0)
        buf.append(s, s.length - keep, s.length)
        return emit
    }

    /** 流结束时冲刷剩余缓冲。 */
    fun flush(): String {
        if (suppressed) return ""
        val s = buf.toString()
        buf.setLength(0)
        return s
    }

    /** 计算 s 末尾有多少个字符可能是某个 marker 的前缀（用于跨增量的部分匹配）。 */
    private fun partialPrefixLen(s: String): Int {
        val maxCheck = minOf(maxMarker - 1, s.length)
        for (len in maxCheck downTo 1) {
            val tail = s.substring(s.length - len)
            if (markers.any { it.length > len && it.regionMatches(0, tail, 0, len, ignoreCase = true) }) {
                return len
            }
        }
        return 0
    }
}

