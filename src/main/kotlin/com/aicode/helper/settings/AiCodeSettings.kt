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
        var temperature: Double = 0.7
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

    companion object {
        fun getInstance(): AiCodeSettings =
            ApplicationManager.getApplication().getService(AiCodeSettings::class.java)
    }
}
