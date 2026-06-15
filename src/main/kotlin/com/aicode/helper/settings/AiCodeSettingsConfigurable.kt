package com.aicode.helper.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
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

    // —— Agent harness 设置 ——
    private val enableToolsCheck = JBCheckBox("启用工具调用（Agent 模式）")
    private val autoApproveReadOnlyCheck = JBCheckBox("自动放行只读工具")
    private val allowWriteToolsCheck = JBCheckBox("允许写/执行类工具（谨慎开启）")
    private val enableAutocompactCheck = JBCheckBox("启用 autocompact 自动上下文压缩")
    private val maxIterationsSpinner = JSpinner(SpinnerNumberModel(25, 1, 200, 1))
    private val contextWindowSpinner = JSpinner(SpinnerNumberModel(128000, 4000, 2000000, 1000))
    private val summaryModelField = JBTextField()

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
            .addSeparator()
            .addComponent(JBLabel("Agent / Harness 设置"))
            .addComponent(enableToolsCheck)
            .addComponent(autoApproveReadOnlyCheck)
            .addComponent(allowWriteToolsCheck)
            .addComponent(enableAutocompactCheck)
            .addLabeledComponent(JBLabel("最大迭代次数:"), maxIterationsSpinner, 1, false)
            .addLabeledComponent(JBLabel("上下文窗口 (tokens):"), contextWindowSpinner, 1, false)
            .addLabeledComponent(JBLabel("摘要模型(可空):"), summaryModelField, 1, false)
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
                temperatureSlider.value != (settings.temperature * 100).toInt() ||
                enableToolsCheck.isSelected != settings.enableTools ||
                autoApproveReadOnlyCheck.isSelected != settings.autoApproveReadOnlyTools ||
                allowWriteToolsCheck.isSelected != settings.allowWriteTools ||
                enableAutocompactCheck.isSelected != settings.enableAutocompact ||
                maxIterationsSpinner.value as Int != settings.maxAgentIterations ||
                contextWindowSpinner.value as Int != settings.contextWindowTokens ||
                summaryModelField.text != settings.summaryModelName
    }

    override fun apply() {
        val settings = AiCodeSettings.getInstance()
        settings.apiUrl = apiUrlField.text.trim()
        settings.apiKey = String(apiKeyField.password).trim()
        settings.modelName = modelNameField.text.trim()
        settings.maxTokens = maxTokensSpinner.value as Int
        settings.temperature = temperatureSlider.value / 100.0
        settings.enableTools = enableToolsCheck.isSelected
        settings.autoApproveReadOnlyTools = autoApproveReadOnlyCheck.isSelected
        settings.allowWriteTools = allowWriteToolsCheck.isSelected
        settings.enableAutocompact = enableAutocompactCheck.isSelected
        settings.maxAgentIterations = maxIterationsSpinner.value as Int
        settings.contextWindowTokens = contextWindowSpinner.value as Int
        settings.summaryModelName = summaryModelField.text.trim()
    }

    override fun reset() {
        val settings = AiCodeSettings.getInstance()
        apiUrlField.text = settings.apiUrl
        apiKeyField.text = settings.apiKey
        modelNameField.text = settings.modelName
        maxTokensSpinner.value = settings.maxTokens
        temperatureSlider.value = (settings.temperature * 100).toInt()
        temperatureLabel.text = String.format("%.2f", settings.temperature)
        enableToolsCheck.isSelected = settings.enableTools
        autoApproveReadOnlyCheck.isSelected = settings.autoApproveReadOnlyTools
        allowWriteToolsCheck.isSelected = settings.allowWriteTools
        enableAutocompactCheck.isSelected = settings.enableAutocompact
        maxIterationsSpinner.value = settings.maxAgentIterations
        contextWindowSpinner.value = settings.contextWindowTokens
        summaryModelField.text = settings.summaryModelName
    }
}
