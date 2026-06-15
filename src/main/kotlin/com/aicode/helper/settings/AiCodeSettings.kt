package com.aicode.helper.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "AiCodeSettings",
    storages = [Storage("AiCodeHelperSettings.xml")]
)
class AiCodeSettings : PersistentStateComponent<AiCodeSettings.State> {

    data class State(
        var apiUrl: String = "https://api.openai.com",
        var apiKey: String = "",
        var modelName: String = "gpt-3.5-turbo",
        var maxTokens: Int = 2048,
        var temperature: Double = 0.7,
        // —— Agent harness 相关 ——
        /** 估算上下文窗口（tokens），用于 autocompact 阈值判断。 */
        var contextWindowTokens: Int = 128_000,
        /** 压缩摘要使用的更便宜模型；留空则用主模型。 */
        var summaryModelName: String = "",
        /** 是否启用工具调用（agent 模式）。 */
        var enableTools: Boolean = true,
        /** 自动放行只读工具。 */
        var autoApproveReadOnlyTools: Boolean = true,
        /** 允许写/执行类工具（默认关闭，需显式开启）。 */
        var allowWriteTools: Boolean = false,
        /** 是否启用 L3 autocompact 自动压缩。 */
        var enableAutocompact: Boolean = true,
        /** agent 主循环最大迭代次数（断路器）。 */
        var maxAgentIterations: Int = 25
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var apiUrl: String
        get() = myState.apiUrl
        set(value) { myState.apiUrl = value }

    var apiKey: String
        get() = myState.apiKey
        set(value) { myState.apiKey = value }

    var modelName: String
        get() = myState.modelName
        set(value) { myState.modelName = value }

    var maxTokens: Int
        get() = myState.maxTokens
        set(value) { myState.maxTokens = value }

    var temperature: Double
        get() = myState.temperature
        set(value) { myState.temperature = value }

    var contextWindowTokens: Int
        get() = myState.contextWindowTokens
        set(value) { myState.contextWindowTokens = value }

    var summaryModelName: String
        get() = myState.summaryModelName
        set(value) { myState.summaryModelName = value }

    var enableTools: Boolean
        get() = myState.enableTools
        set(value) { myState.enableTools = value }

    var autoApproveReadOnlyTools: Boolean
        get() = myState.autoApproveReadOnlyTools
        set(value) { myState.autoApproveReadOnlyTools = value }

    var allowWriteTools: Boolean
        get() = myState.allowWriteTools
        set(value) { myState.allowWriteTools = value }

    var enableAutocompact: Boolean
        get() = myState.enableAutocompact
        set(value) { myState.enableAutocompact = value }

    var maxAgentIterations: Int
        get() = myState.maxAgentIterations
        set(value) { myState.maxAgentIterations = value }

    companion object {
        fun getInstance(): AiCodeSettings =
            ApplicationManager.getApplication().getService(AiCodeSettings::class.java)
    }
}
