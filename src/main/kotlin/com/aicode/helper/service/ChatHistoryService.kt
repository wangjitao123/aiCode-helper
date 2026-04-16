package com.aicode.helper.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ChatHistoryService {

    data class ChatMessage(val role: String, val content: String)

    private val history = mutableListOf<ChatMessage>()

    fun addMessage(role: String, content: String) {
        history.add(ChatMessage(role, content))
    }

    fun getHistory(): List<ChatMessage> = history.toList()

    fun clear() {
        history.clear()
    }

    fun toApiMessages(): List<AiApiService.Message> {
        return history.map { AiApiService.Message(it.role, it.content) }
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService =
            project.getService(ChatHistoryService::class.java)
    }
}
