package com.panda.ai.v2

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.panda.ai.v2.actions.ActionExecutor
import com.panda.ai.v2.fs.FileSystem
import com.panda.ai.v2.llm.GeminiApi
import com.panda.ai.v2.llm.GeminiMessage
import com.panda.ai.v2.message_manager.MemoryManager
import com.panda.ai.v2.perception.Perception
import com.panda.ai.utilities.SpeechCoordinator
import com.panda.ai.overlay.OverlayDispatcher
import com.panda.ai.overlay.OverlayPriority
import com.panda.ai.overlay.OverlayPosition
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.R)
class Agent(
    private val settings: AgentSettings,
    private val memoryManager: MemoryManager,
    private val perception: Perception,
    private val llmApi: GeminiApi,
    private val actionExecutor: ActionExecutor,
    private val fileSystem: FileSystem,
    private val context: Context
) {
    val state: AgentState = AgentState()
    private val TAG = "AgentV2"
    private val speechCoordinator = SpeechCoordinator.getInstance(context)
    val history: AgentHistoryList<Unit> = AgentHistoryList()

    suspend fun run(initialTask: String, maxSteps: Int = 150) {
        memoryManager.addNewTask(initialTask)
        state.stopped = false
        Log.d(TAG, "--- Agent starting task: '$initialTask' ---")

        while (!state.stopped && state.nSteps <= maxSteps) {
            Log.d(TAG, "\n--- Step ${state.nSteps}/$maxSteps ---")

            // 1. SENSE
            Log.d(TAG, "👀 Sensing screen state...")
            val screenState = perception.analyze()

            // 2. THINK - Prepare prompt
            Log.d(TAG, "🧠 Preparing prompt...")
            memoryManager.createStateMessage(
                modelOutput = state.lastModelOutput,
                result = state.lastResult,
                stepInfo = AgentStepInfo(state.nSteps, maxSteps),
                screenState = screenState
            )

            // 3. THINK - Get decision
            Log.d(TAG, "🤔 Asking LLM for next action...")
            val messages = memoryManager.getMessages()
            val agentOutput = llmApi.generateAgentOutput(messages)

            if (agentOutput == null) {
                Log.d(TAG, "❌ LLM failed to return a valid action. Retrying...")
                state.consecutiveFailures++
                memoryManager.addContextMessage(
                    GeminiMessage(text = "System Note: Your previous output was not valid JSON. Please ensure your response is correctly formatted.")
                )
                if (state.consecutiveFailures >= settings.maxFailures) {
                    Log.d(TAG, "❌ Agent failed too many times. Stopping.")
                    speechCoordinator.speakToUser("Agent failed after multiple attempts. Stopping execution.")
                    break
                }
                delay(500)
                continue
            }

            state.consecutiveFailures = 0
            state.lastModelOutput = agentOutput
            Log.d(TAG, "🤖 LLM decided: ${agentOutput.nextGoal}")

            // Show thoughts overlay — KEY_SHOW_THOUGHTS hardcoded false (no SettingsActivity)
            val showThoughts = context.getSharedPreferences("HoneySettings", Context.MODE_PRIVATE)
                .getBoolean("show_thoughts", false)

            if (showThoughts) {
                val thoughtText = buildString {
                    agentOutput.thinking?.let { if (it.isNotEmpty()) append("Thinking: $it\n") }
                    agentOutput.memory?.let { if (it.isNotEmpty()) append("Memory: $it\n") }
                    agentOutput.nextGoal?.let { if (it.isNotEmpty()) append("Next Goal: $it") }
                }.trim()

                if (thoughtText.isNotEmpty()) {
                    OverlayDispatcher.show(
                        text = thoughtText,
                        priority = OverlayPriority.TASKS,
                        duration = 8000L,
                        position = OverlayPosition.TOP
                    )
                }
            }

            // 4. ACT
            Log.d(TAG, "💪 Executing actions...")
            val actionResults = mutableListOf<ActionResult>()
            for (action in agentOutput.action) {
                val result = actionExecutor.execute(action, screenState, context, fileSystem)
                actionResults.add(result)
                Log.d(TAG, "  - Action '${action::class.simpleName}' result: ${result.longTermMemory ?: result.error ?: "OK"}")
                if (result.error != null) {
                    Log.d(TAG, "  - 🛑 Action failed. Stopping current step.")
                    break
                }
            }
            state.lastResult = actionResults

            // 5. RECORD
            history.addItem(
                AgentHistory(
                    modelOutput = agentOutput,
                    result = actionResults,
                    state = screenState,
                    metadata = null
                )
            )

            // Check task completion
            if (actionResults.any { it.isDone == true }) {
                Log.d(TAG, "✅ Agent finished the task.")
                speechCoordinator.speakToUser("Task completed successfully.")
                state.stopped = true
            }

            state.nSteps++
            delay(500)
        }

        if (state.nSteps > maxSteps) {
            Log.d(TAG, "--- 🏁 Agent reached max steps. Stopping. ---")
            speechCoordinator.speakToUser("Agent reached maximum steps limit. Stopping execution.")
        } else {
            Log.d(TAG, "--- 🏁 Agent run finished. ---")
        }
    }
}