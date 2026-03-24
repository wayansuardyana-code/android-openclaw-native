package com.openclaw.android.ai

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.android.OpenClawApplication

/**
 * Persists AI agent configuration (API keys, active provider, model).
 * Uses SharedPreferences for simplicity — encrypted in production.
 */
object AgentConfig {
    private const val PREFS = "openclaw_agent"

    private fun prefs(): SharedPreferences =
        OpenClawApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var activeProvider: String
        get() = prefs().getString("active_provider", "minimax") ?: "minimax"
        set(v) = prefs().edit().putString("active_provider", v).apply()

    // API Keys
    var anthropicKey: String
        get() = prefs().getString("key_anthropic", "") ?: ""
        set(v) = prefs().edit().putString("key_anthropic", v).apply()

    var openaiKey: String
        get() = prefs().getString("key_openai", "") ?: ""
        set(v) = prefs().edit().putString("key_openai", v).apply()

    var minimaxKey: String
        get() = prefs().getString("key_minimax", "") ?: ""
        set(v) = prefs().edit().putString("key_minimax", v).apply()

    var googleKey: String
        get() = prefs().getString("key_google", "") ?: ""
        set(v) = prefs().edit().putString("key_google", v).apply()

    var openrouterKey: String
        get() = prefs().getString("key_openrouter", "") ?: ""
        set(v) = prefs().edit().putString("key_openrouter", v).apply()

    var customKey: String
        get() = prefs().getString("key_custom", "") ?: ""
        set(v) = prefs().edit().putString("key_custom", v).apply()

    var customBaseUrl: String
        get() = prefs().getString("custom_base_url", "") ?: ""
        set(v) = prefs().edit().putString("custom_base_url", v).apply()

    var customModel: String
        get() = prefs().getString("custom_model", "") ?: ""
        set(v) = prefs().edit().putString("custom_model", v).apply()

    /**
     * Build LlmClient.Config from current settings.
     */
    fun toLlmConfig(): LlmClient.Config {
        return when (activeProvider) {
            "anthropic" -> LlmClient.Config("anthropic", anthropicKey, "claude-sonnet-4-6", "https://api.anthropic.com")
            "openai" -> LlmClient.Config("openai", openaiKey, "gpt-4o", "https://api.openai.com")
            "minimax" -> LlmClient.Config("anthropic", minimaxKey, "MiniMax-M1-80k", "https://api.minimax.io/anthropic")
            "google" -> LlmClient.Config("openai", googleKey, "gemini-2.5-flash", "https://generativelanguage.googleapis.com")
            "openrouter" -> LlmClient.Config("openai", openrouterKey, "anthropic/claude-sonnet-4-6", "https://openrouter.ai/api")
            "ollama" -> LlmClient.Config("openai", "", "llama3.2", "http://localhost:11434")
            "custom" -> LlmClient.Config("openai", customKey, customModel, customBaseUrl)
            else -> LlmClient.Config("anthropic", minimaxKey, "MiniMax-M1-80k", "https://api.minimax.io/anthropic")
        }
    }
}
