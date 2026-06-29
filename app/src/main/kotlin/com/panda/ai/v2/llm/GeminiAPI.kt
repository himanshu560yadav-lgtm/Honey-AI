package com.panda.ai.v2.llm

import android.content.Context
import android.util.Log
import com.panda.ai.utilities.ApiKeyManager
import com.panda.ai.v2.AgentOutput
import com.panda.ai.v2.logging.TaskLogger
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class GeminiApi(
    private val modelName: String,
    private val context: Context,
    private val maxRetry: Int = 3
) {
    companion object {
        private const val TAG = "GeminiV2Api"
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val modelCache = ConcurrentHashMap<String, GenerativeModel>()

    private val jsonGenerationConfig = GenerationConfig.builder().apply {
        responseMimeType = "application/json"
    }.build()

    private val requestOptions = RequestOptions(timeout = 30.seconds)

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performDirectApiCall(messages)
        } ?: return null

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log task: ${e.message}")
        }

        return try {
            Log.d(TAG, "Parsing response: $jsonString")
            jsonParser.decodeFromString<AgentOutput>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
            null
        }
    }

    private suspend fun performDirectApiCall(messages: List<GeminiMessage>): String {
        val apiKey = ApiKeyManager.getApiKey(context)
        if (apiKey.isEmpty()) throw Exception("API key not set!")

        val generativeModel = modelCache.getOrPut(apiKey) {
            GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = jsonGenerationConfig,
                requestOptions = requestOptions
            )
        }

        val history = messages.map { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.MODEL -> "model"
                MessageRole.TOOL -> "tool"
            }
            content(role) {
                message.parts.forEach { part ->
                    if (part is TextPart) text(part.text)
                }
            }
        }

        val response = generativeModel.generateContent(*history.toTypedArray())
        response.text?.let { return it }

        val reason = response.promptFeedback?.blockReason?.name ?: "UNKNOWN"
        throw ContentBlockedException("Blocked: $reason")
    }
}

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 500L,
    maxDelay: Long = 8000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}")
            if (attempt == times - 1) return null
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}