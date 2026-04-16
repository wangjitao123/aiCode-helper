package com.aicode.helper.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AiCodeSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private val apiUrlField = JBTextField()
    private val apiKeyField = JPasswordField()
    private val modelNameField = JBTextField()
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(2048, 1, 32000, 1))
    private val temperatureSlider = JSlider(0, 100, 70)
    private val temperatureLabel = JBLabel("0.70")

    override fun getDisplayName(): String = "AI Code Helper"

    override fun createComponent(): JComponent {
        temperatureSlider.addChangeListener {
            val value = temperatureSlider.value / 100.0
            temperatureLabel.text = String.format("%.2f", value)
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API 地址:"), apiUrlField, 1, false)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("模型名称:"), modelNameField, 1, false)
            .addLabeledComponent(JBLabel("最大 Token 数:"), maxTokensSpinner, 1, false)
            .addLabeledComponent(JBLabel("Temperature:"), temperatureSlider, 1, false)
            .addLabeledComponent(JBLabel("当前 Temperature 值:"), temperatureLabel, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = AiCodeSettings.getInstance()
        return apiUrlField.text != settings.apiUrl ||
                String(apiKeyField.password) != settings.apiKey ||
                modelNameField.text != settings.modelName ||
                maxTokensSpinner.value as Int != settings.maxTokens ||
                temperatureSlider.value != (settings.temperature * 100).toInt()
    }

    override fun apply() {
        val settings = AiCodeSettings.getInstance()
        settings.apiUrl = apiUrlField.text.trim()
        settings.apiKey = String(apiKeyField.password).trim()
        settings.modelName = modelNameField.text.trim()
        settings.maxTokens = maxTokensSpinner.value as Int
        settings.temperature = temperatureSlider.value / 100.0
    }

    override fun reset() {
        val settings = AiCodeSettings.getInstance()
        apiUrlField.text = settings.apiUrl
        apiKeyField.text = settings.apiKey
        modelNameField.text = settings.modelName
        maxTokensSpinner.value = settings.maxTokens
        temperatureSlider.value = (settings.temperature * 100).toInt()
        temperatureLabel.text = String.format("%.2f", settings.temperature)
    }
}
