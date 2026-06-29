package com.panda.ai.v2

import android.content.Context
import android.util.Log
import com.panda.ai.v2.actions.Action
import com.panda.ai.v2.fs.FileSystem
import com.panda.ai.v2.llm.GeminiMessage
import com.panda.ai.v2.llm.MessageRole
import com.panda.ai.v2.llm.TextPart
import com.panda.ai.intents.IntentRegistry
import java.io.IOException

private const val DEFAULT_PROMPT_TEMPLATE = "prompts/system_prompt.md"

class SystemPromptLoader(private val context: Context) {

    fun getSystemMessage(settings: AgentSettings): GeminiMessage {
        val actionsDescription = generateActionsDescription()
        val intentsCatalog = generateIntentsCatalog()

        // UserProfileManager hataya — hardcode kar diya
        val userInfo = "Name: User"

        var prompt = settings.overrideSystemMessage ?: loadDefaultTemplate()
            .replace("{max_actions}", settings.maxActionsPerStep.toString())
            .replace("{available_actions}", actionsDescription)
            .replace("{user_info}", userInfo)

        if (intentsCatalog.isNotBlank()) {
            prompt += "\n\n<intents_catalog>\n$intentsCatalog\n</intents_catalog>\n\n" +
                "Usage: To launch any of the above intents, add an action like {\"launch_intent\": {\"intent_name\": \"Dial\", \"parameters\": {\"phone_number\": \"+123456789\"}}}."
        }

        if (!settings.extendSystemMessage.isNullOrBlank()) {
            prompt += "\n${settings.extendSystemMessage}"
        }

        Log.d("SYSTEM_PROMPT_BUILDER", prompt)
        return GeminiMessage(role = MessageRole.USER, parts = listOf(TextPart(prompt)))
    }

    private fun generateActionsDescription(): String {
        val allActionSpecs = Action.getAllSpecs()
        return buildString {
            allActionSpecs.forEach { spec ->
                append("<action>\n")
                append("  <name>${spec.name}</name>\n")
                append("  <description>${spec.description}</description>\n")
                if (spec.params.isNotEmpty()) {
                    append("  <parameters>\n")
                    spec.params.forEach { param ->
                        append("    <param>\n")
                        append("      <name>${param.name}</name>\n")
                        append("      <type>${param.type.simpleName}</type>\n")
                        append("      <description>${param.description}</description>\n")
                        append("    </param>\n")
                    }
                    append("  </parameters>\n")
                }
                append("</action>\n\n")
            }
        }.trim()
    }

    private fun generateIntentsCatalog(): String {
        val intents = IntentRegistry.listIntents(context)
        if (intents.isEmpty()) return ""
        return buildString {
            intents.forEach { intent ->
                append("<intent>\n")
                append("  <name>${intent.name}</name>\n")
                append("  <description>${intent.description()}</description>\n")
                val params = intent.parametersSpec()
                if (params.isNotEmpty()) {
                    append("  <parameters>\n")
                    params.forEach { p ->
                        append("    <param>\n")
                        append("      <name>${p.name}</name>\n")
                        append("      <type>${p.type}</type>\n")
                        append("      <required>${p.required}</required>\n")
                        append("      <description>${p.description}</description>\n")
                        append("    </param>\n")
                    }
                    append("  </parameters>\n")
                }
                append("</intent>\n\n")
            }
        }.trim()
    }

    private fun loadDefaultTemplate(): String {
        return try {
            context.assets.open(DEFAULT_PROMPT_TEMPLATE).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load system prompt: $DEFAULT_PROMPT_TEMPLATE", e)
        }
    }
}

object UserMessageBuilder {

    data class Args(
        val task: String,
        val screenState: ScreenState,
        val fileSystem: FileSystem,
        val agentHistoryDescription: String?,
        val readStateDescription: String?,
        val stepInfo: AgentStepInfo?,
        val sensitiveDataDescription: String?,
        val availableFilePaths: List<String>?,
        val maxUiRepresentationLength: Int = 40000
    )

    fun build(args: Args): GeminiMessage {
        val messageContent = buildString {
            append("<agent_history>\n")
            append(args.agentHistoryDescription?.trim() ?: "No history yet.")
            append("\n</agent_history>\n\n")

            append("<agent_state>\n")
            append(buildAgentStateBlock(args))
            append("\n</agent_state>\n\n")

            append("<android_state>\n")
            append(buildAndroidStateBlock(args.screenState, args.maxUiRepresentationLength))
            append("\n</android_state>\n\n")

            if (!args.readStateDescription.isNullOrBlank()) {
                append("<read_state>\n")
                append(args.readStateDescription.trim())
                append("\n</read_state>\n\n")
            }
        }
        return GeminiMessage(text = messageContent.trim())
    }

    private fun buildAndroidStateBlock(screenState: ScreenState, maxUiRepresentationLength: Int): String {
        val originalUiString = screenState.uiRepresentation
        val truncationMessage: String
        val finalUiString: String

        if (originalUiString.length > maxUiRepresentationLength) {
            finalUiString = originalUiString.substring(0, maxUiRepresentationLength)
            truncationMessage = " (truncated to $maxUiRepresentationLength characters)"
        } else {
            finalUiString = originalUiString
            truncationMessage = ""
        }

        return buildString {
            appendLine("Current Activity: ${screenState.activityName}")
            appendLine("Visible elements on the current screen:$truncationMessage")
            append(finalUiString)
        }.trim()
    }

    private fun buildAgentStateBlock(args: Args): String {
        val todoContents = args.fileSystem.getTodoContents().let {
            it.ifBlank { "[Current todo.md is empty, fill it with your plan when applicable]" }
        }

        val stepInfoDescription = args.stepInfo?.let {
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            "Step ${it.stepNumber + 1} of ${it.maxSteps} max possible steps\nCurrent date and time: $timeStr"
        } ?: "Step information not available."

        return buildString {
            appendLine("<user_request>")
            appendLine(args.task)
            appendLine("</user_request>")

            appendLine("<file_system>")
            appendLine(args.fileSystem.describe())
            appendLine("</file_system>")

            appendLine("<todo_contents>")
            appendLine(todoContents)
            appendLine("</todo_contents>")

            if (!args.sensitiveDataDescription.isNullOrBlank()) {
                appendLine("<sensitive_data>")
                appendLine(args.sensitiveDataDescription)
                appendLine("</sensitive_data>")
            }

            appendLine("<step_info>")
            appendLine(stepInfoDescription)
            appendLine("</step_info>")

            if (!args.availableFilePaths.isNullOrEmpty()) {
                appendLine("<available_file_paths>")
                appendLine(args.availableFilePaths.joinToString("\n"))
                appendLine("</available_file_paths>")
            }
        }.trim()
    }
}