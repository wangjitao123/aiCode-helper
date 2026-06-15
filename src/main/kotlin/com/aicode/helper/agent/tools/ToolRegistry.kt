package com.aicode.helper.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 工具系统（决策 #4）：注册中心 + 向 API 暴露工具定义。
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    fun find(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /** 生成 OpenAI tools 数组（每项 {type:"function", function:{name,description,parameters}}）。 */
    fun toolsJson(): List<JsonObject> = tools.values.map { tool ->
        JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("parameters", tool.parameters())
            })
        }
    }

    companion object {
        /** 构造一个 JSON Schema object 类型的参数定义。 */
        fun objectSchema(vararg props: Triple<String, String, String>, required: List<String> = emptyList()): JsonObject {
            val properties = JsonObject()
            for ((name, type, desc) in props) {
                properties.add(name, JsonObject().apply {
                    addProperty("type", type)
                    addProperty("description", desc)
                })
            }
            return JsonObject().apply {
                addProperty("type", "object")
                add("properties", properties)
                add("required", JsonArray().apply { required.forEach { add(it) } })
            }
        }
    }
}

