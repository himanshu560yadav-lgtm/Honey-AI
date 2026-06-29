package com.panda.ai.v2.message_manager

import android.content.Context
import com.panda.ai.v2.ActionResult
import com.panda.ai.v2.AgentOutput
import com.panda.ai.v2.AgentSettings
import com.panda.ai.v2.AgentStepInfo
import com.panda.ai.v2.ScreenState
import com.panda.ai.v2.SystemPromptLoader
import com.panda.ai.v2.UserMessageBuilder
import com.panda.ai.v2.fs.FileSystem
import com.panda.ai.v2.llm.GeminiMessage
import com.panda.ai.v2.llm.TextPart

class MemoryManager(
    context: Context,
    private var task: String,
    private val fileSystem: FileSystem,
    private val settings: AgentSettings,
    private val sensitiveData: Map<String, String>? = null,
    initialState: MemoryState = MemoryState()
) {
    val state: MemoryState = initialState

    init {
        if (state.history.systemMessage == null) {
            val systemPromptLoader = SystemPromptLoader(context)
            val systemMessage = systemPromptLoader.getSystemMessage(settings)
            state.history.systemMessage = filterSensitiveData(systemMessage)
        }
    }

    fun createStateMessage(
        modelOutput: AgentOutput?,
        result: List<ActionResult>?,
        stepInfo: AgentStepInfo?,
        screenState: ScreenState
    ) {
        updateHistory(modelOutput, result, stepInfo)

        val builderArgs = UserMessageBuilder.Args(
            task = this.task,
            screenState = screenState,
            fileSystem = this.fileSystem,
            agentHistoryDescription = getAgentHistoryDescription(),
            readStateDescription = state.readStateDescription,
            stepInfo = stepInfo,
            sensitiveDataDescription = getSensitiveDataDescription(),
            availableFilePaths = null
        )

        var stateMessage = UserMessageBuilder.build(builderArgs)
        stateMessage = filterSensitiveData(stateMessage)

        state.history.stateMessage = stateMessage
        state.history.contextMessages.clear()
    }

    fun addNewTask(newTask: String) {
        this.task = newTask
        val taskUpdateItem = HistoryItem(
            stepNumber = 0,
            systemMessage = "<user_request> added: $newTask"
        )
        state.agentHistoryItems.add(taskUpdateItem)
    }

    fun addContextMessage(message: GeminiMessage) {
        state.history.contextMessages.add(message)
    }

    fun getMessages(): List<GeminiMessage> {
        return state.history.getMessages()
    }

    private fun updateHistory(
        modelOutput: AgentOutput?,
        result: List<ActionResult>?,
        stepInfo: AgentStepInfo?
    ) {
        state.readStateDescription = ""

        val actionResultsText = result?.mapIndexedNotNull { index, actionResult ->
            if (actionResult.includeExtractedContentOnlyOnce && !actionResult.extractedContent.isNullOrBlank()) {
                state.readStateDescription += actionResult.extractedContent + "\n"
            }
            when {
                !actionResult.longTermMemory.isNullOrBlank() -> "Action ${index + 1}: ${actionResult.longTermMemory}"
                !actionResult.extractedContent.isNullOrBlank() && !actionResult.includeExtractedContentOnlyOnce -> "Action ${index + 1}: ${actionResult.extractedContent}"
                !actionResult.error.isNullOrBlank() -> "Action ${index + 1}: ERROR - ${actionResult.error.take(200)}"
                else -> null
            }
        }?.joinToString("\n")

        val historyItem = if (modelOutput == null) {
            if (stepInfo?.stepNumber != 1) {
                HistoryItem(stepNumber = stepInfo?.stepNumber, error = "Agent failed to produce a valid output.")
            } else {
                HistoryItem(stepNumber = stepInfo.stepNumber, error = "Agent not asked to create output yet")
            }
        } else {
            HistoryItem(
                stepNumber = stepInfo?.stepNumber,
                evaluation = modelOutput.evaluationPreviousGoal,
                memory = modelOutput.memory,
                nextGoal = modelOutput.nextGoal,
                actionResults = actionResultsText?.let { "Action Results:\n$it" }
            )
        }
        state.agentHistoryItems.add(historyItem)
    }

    private fun getAgentHistoryDescription(): String {
        val items = state.agentHistoryItems
        val maxItems = settings.maxHistoryItems ?: items.size

        if (items.size <= maxItems) {
            return items.joinToString("\n") { it.toPromptString() }
        }

        val omittedCount = items.size - maxItems
        val recentItemsCount = maxItems - 1

        val result = mutableListOf<String>()
        result.add(items.first().toPromptString())
        result.add("<sys>[... $omittedCount previous steps omitted...]</sys>")
        result.addAll(items.takeLast(recentItemsCount).map { it.toPromptString() })

        return result.joinToString("\n")
    }

    private fun getSensitiveDataDescription(): String? {
        val placeholders = sensitiveData?.keys
        if (placeholders.isNullOrEmpty()) return null
        return "Here are placeholders for sensitive data:\n${placeholders.joinToString()}\nTo use them, write <secret>the placeholder name</secret>"
    }

    private fun filterSensitiveData(message: GeminiMessage): GeminiMessage {
        if (sensitiveData.isNullOrEmpty()) return message
        val newParts = message.parts.map { part ->
            if (part is TextPart) {
                var newText = part.text
                sensitiveData.forEach { (key, value) ->
                    newText = newText.replace(value, "<secret>$key</secret>")
                }
                TextPart(newText)
            } else part
        }
        return message.copy(parts = newParts)
    }
}