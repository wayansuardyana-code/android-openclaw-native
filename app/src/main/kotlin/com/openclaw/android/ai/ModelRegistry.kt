package com.openclaw.android.ai

/**
 * All supported LLM models per provider.
 * Mirrors OpenClaw's model support + major providers.
 */
object ModelRegistry {

    data class ModelInfo(
        val id: String,           // API model ID
        val displayName: String,  // Human-readable name
        val provider: String      // Provider key
    )

    private val models = listOf(
        // ── Anthropic ──
        ModelInfo("claude-opus-4-6", "Claude Opus 4.6", "anthropic"),
        ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", "anthropic"),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", "anthropic"),
        ModelInfo("claude-sonnet-4-5-20250514", "Claude Sonnet 4.5", "anthropic"),

        // ── OpenAI ──
        ModelInfo("gpt-5.4", "GPT-5.4", "openai"),
        ModelInfo("gpt-4.1", "GPT-4.1", "openai"),
        ModelInfo("gpt-4.1-mini", "GPT-4.1 Mini", "openai"),
        ModelInfo("gpt-4.1-nano", "GPT-4.1 Nano", "openai"),
        ModelInfo("gpt-4o", "GPT-4o", "openai"),
        ModelInfo("gpt-4o-mini", "GPT-4o Mini", "openai"),
        ModelInfo("o3", "o3", "openai"),
        ModelInfo("o3-mini", "o3-mini", "openai"),
        ModelInfo("o4-mini", "o4-mini", "openai"),

        // ── MiniMax ──
        ModelInfo("MiniMax-M1-80k", "MiniMax M1 80K", "minimax"),
        ModelInfo("MiniMax-M2.7", "MiniMax M2.7 (Reasoning)", "minimax"),
        ModelInfo("MiniMax-VL-01", "MiniMax VL-01 (Vision)", "minimax"),
        ModelInfo("MiniMax-M2.5", "MiniMax M2.5", "minimax"),
        ModelInfo("MiniMax-M2.5-Highspeed", "MiniMax M2.5 Highspeed", "minimax"),
        ModelInfo("MiniMax-M2.1", "MiniMax M2.1", "minimax"),

        // ── Google ──
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", "google"),
        ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", "google"),
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", "google"),

        // ── DeepSeek ──
        ModelInfo("deepseek-chat", "DeepSeek V3", "deepseek"),
        ModelInfo("deepseek-reasoner", "DeepSeek R1", "deepseek"),

        // ── Mistral ──
        ModelInfo("mistral-large-latest", "Mistral Large", "mistral"),
        ModelInfo("mistral-small-latest", "Mistral Small", "mistral"),
        ModelInfo("codestral-latest", "Codestral", "mistral"),

        // ── Groq ──
        ModelInfo("llama-3.3-70b-versatile", "Llama 3.3 70B", "groq"),
        ModelInfo("llama-3.1-8b-instant", "Llama 3.1 8B", "groq"),
        ModelInfo("mixtral-8x7b-32768", "Mixtral 8x7B", "groq"),
        ModelInfo("gemma2-9b-it", "Gemma 2 9B", "groq"),

        // ── xAI ──
        ModelInfo("grok-2", "Grok 2", "xai"),
        ModelInfo("grok-3", "Grok 3", "xai"),
        ModelInfo("grok-3-mini", "Grok 3 Mini", "xai"),

        // ── Together AI ──
        ModelInfo("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo", "together"),
        ModelInfo("Qwen/Qwen2.5-72B-Instruct-Turbo", "Qwen 2.5 72B Turbo", "together"),
        ModelInfo("deepseek-ai/DeepSeek-V3", "DeepSeek V3", "together"),

        // ── Fireworks ──
        ModelInfo("accounts/fireworks/models/llama-v3p3-70b-instruct", "Llama 3.3 70B", "fireworks"),
        ModelInfo("accounts/fireworks/models/qwen2p5-72b-instruct", "Qwen 2.5 72B", "fireworks"),

        // ── OpenRouter ──
        ModelInfo("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6", "openrouter"),
        ModelInfo("openai/gpt-4o", "GPT-4o", "openrouter"),
        ModelInfo("google/gemini-2.5-flash", "Gemini 2.5 Flash", "openrouter"),
        ModelInfo("deepseek/deepseek-chat", "DeepSeek V3", "openrouter"),
        ModelInfo("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B", "openrouter"),

        // ── Ollama ──
        ModelInfo("llama3.2", "Llama 3.2", "ollama"),
        ModelInfo("llama3.2:1b", "Llama 3.2 1B", "ollama"),
        ModelInfo("mistral", "Mistral 7B", "ollama"),
        ModelInfo("codellama", "Code Llama", "ollama"),
        ModelInfo("qwen2.5", "Qwen 2.5", "ollama"),
        ModelInfo("gemma2", "Gemma 2", "ollama"),
        ModelInfo("phi3", "Phi-3", "ollama"),
    )

    fun getModelsForProvider(provider: String): List<ModelInfo> =
        models.filter { it.provider == provider }

    fun getDefaultModel(provider: String): String =
        getModelsForProvider(provider).firstOrNull()?.id ?: ""

    fun getAllProviders(): List<String> = models.map { it.provider }.distinct()
}
