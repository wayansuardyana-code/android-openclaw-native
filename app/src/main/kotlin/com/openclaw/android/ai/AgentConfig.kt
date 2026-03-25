package com.openclaw.android.ai

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.android.OpenClawApplication

/**
 * Persists AI agent configuration (API keys, active provider, model).
 * Supports dynamic provider keys — one key per provider stored in SharedPreferences.
 */
object AgentConfig {
    private const val PREFS = "openclaw_agent"

    private fun prefs(): SharedPreferences =
        OpenClawApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var activeProvider: String
        get() = prefs().getString("active_provider", "minimax") ?: "minimax"
        set(v) = prefs().edit().putString("active_provider", v).apply()

    var pushNotificationsEnabled: Boolean
        get() = prefs().getBoolean("push_notifications", true)
        set(v) = prefs().edit().putBoolean("push_notifications", v).apply()

    // Provider aliases — some providers have multiple names
    private fun resolveProvider(provider: String): String = when (provider) {
        "gemini" -> "google"  // gemini is an alias for google
        else -> provider
    }

    // Dynamic key storage per provider
    fun getKeyForProvider(provider: String): String {
        val key = prefs().getString("key_$provider", "") ?: ""
        // Also check alias
        if (key.isBlank() && provider != resolveProvider(provider)) {
            return prefs().getString("key_${resolveProvider(provider)}", "") ?: ""
        }
        return key
    }

    fun setKeyForProvider(provider: String, key: String) {
        prefs().edit().putString("key_$provider", key).apply()
        // Also save under canonical name
        val canonical = resolveProvider(provider)
        if (canonical != provider) {
            prefs().edit().putString("key_$canonical", key).apply()
        }
    }

    // Default models per provider
    private val defaultModels = mapOf(
        "anthropic" to "claude-sonnet-4-6",
        "openai" to "gpt-4o",
        "minimax" to "MiniMax-M1-80k",
        "google" to "gemini-2.5-flash",
        "gemini" to "gemini-2.5-flash",
        "kimi" to "kimi-k2",
        "moonshot" to "moonshot-v1-auto",
        "openrouter" to "anthropic/claude-sonnet-4-6",
        "deepseek" to "deepseek-chat",
        "mistral" to "mistral-large-latest",
        "groq" to "llama-3.3-70b-versatile",
        "xai" to "grok-2",
        "together" to "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        "fireworks" to "accounts/fireworks/models/llama-v3p3-70b-instruct",
        "ollama" to "llama3.2",
        "custom" to ""
    )

    fun getModelForProvider(provider: String): String =
        prefs().getString("model_$provider", defaultModels[provider] ?: "") ?: ""

    fun setModelForProvider(provider: String, model: String) =
        prefs().edit().putString("model_$provider", model).apply()

    // Base URLs per provider
    private val baseUrls = mapOf(
        "anthropic" to "https://api.anthropic.com",
        "openai" to "https://api.openai.com",
        "minimax" to "https://api.minimax.io/anthropic",
        "google" to "https://generativelanguage.googleapis.com",
        "gemini" to "https://generativelanguage.googleapis.com",
        "kimi" to "https://api.kimi.ai",
        "moonshot" to "https://api.moonshot.cn",
        "openrouter" to "https://openrouter.ai/api",
        "deepseek" to "https://api.deepseek.com",
        "mistral" to "https://api.mistral.ai",
        "groq" to "https://api.groq.com/openai",
        "xai" to "https://api.x.ai",
        "together" to "https://api.together.xyz",
        "fireworks" to "https://api.fireworks.ai/inference",
        "ollama" to "http://localhost:11434",
        "custom" to ""
    )

    var customBaseUrl: String
        get() = prefs().getString("custom_base_url", "") ?: ""
        set(v) = prefs().edit().putString("custom_base_url", v).apply()

    // Backward compat
    var anthropicKey: String
        get() = getKeyForProvider("anthropic")
        set(v) = setKeyForProvider("anthropic", v)
    var openaiKey: String
        get() = getKeyForProvider("openai")
        set(v) = setKeyForProvider("openai", v)
    var minimaxKey: String
        get() = getKeyForProvider("minimax")
        set(v) = setKeyForProvider("minimax", v)
    var openrouterKey: String
        get() = getKeyForProvider("openrouter")
        set(v) = setKeyForProvider("openrouter", v)
    var googleKey: String
        get() = getKeyForProvider("google")
        set(v) = setKeyForProvider("google", v)
    var customKey: String
        get() = getKeyForProvider("custom")
        set(v) = setKeyForProvider("custom", v)
    var customModel: String
        get() = getModelForProvider("custom")
        set(v) = setModelForProvider("custom", v)

    /**
     * Build LlmClient.Config from current settings.
     */
    /**
     * Build config for a specific provider (used by fallback system).
     */
    fun buildConfigForProvider(provider: String): LlmClient.Config {
        val key = getKeyForProvider(provider)
        val model = getModelForProvider(provider)
        val baseUrl = baseUrls[provider] ?: ""
        val apiType = when (provider) {
            "anthropic", "minimax" -> "anthropic"
            else -> "openai"
        }
        return LlmClient.Config(apiType, key, model, baseUrl)
    }

    fun toLlmConfig(): LlmClient.Config {
        val provider = activeProvider
        val key = getKeyForProvider(provider)
        val model = getModelForProvider(provider)
        val baseUrl = if (provider == "custom") customBaseUrl else (baseUrls[provider] ?: "")

        // Anthropic-style API (native /v1/messages)
        val apiType = when (provider) {
            "anthropic", "minimax" -> "anthropic"
            else -> "openai"
        }

        return LlmClient.Config(apiType, key, model, baseUrl)
    }
}
