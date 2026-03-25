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

        // ── Google / Gemini ──
        ModelInfo("gemini-3.1-flash", "Gemini 3.1 Flash", "google"),
        ModelInfo("gemini-3.0-flash", "Gemini 3 Flash", "google"),
        ModelInfo("gemini-3.0-pro", "Gemini 3 Pro", "google"),
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", "google"),
        ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", "google"),
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", "google"),
        // Alias — same models under "gemini" provider name
        ModelInfo("gemini-3.1-flash", "Gemini 3.1 Flash", "gemini"),
        ModelInfo("gemini-3.0-flash", "Gemini 3 Flash", "gemini"),
        ModelInfo("gemini-3.0-pro", "Gemini 3 Pro", "gemini"),
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", "gemini"),
        ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", "gemini"),
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", "gemini"),

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

        // ── OpenRouter (popular models — user can also type any model ID) ──
        ModelInfo("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6", "openrouter"),
        ModelInfo("anthropic/claude-haiku-4-5", "Claude Haiku 4.5", "openrouter"),
        ModelInfo("openai/gpt-5.4", "GPT-5.4", "openrouter"),
        ModelInfo("openai/gpt-4o", "GPT-4o", "openrouter"),
        ModelInfo("openai/gpt-4o-mini", "GPT-4o Mini", "openrouter"),
        ModelInfo("google/gemini-3.1-flash", "Gemini 3.1 Flash", "openrouter"),
        ModelInfo("google/gemini-3.0-pro", "Gemini 3 Pro", "openrouter"),
        ModelInfo("google/gemini-2.5-flash", "Gemini 2.5 Flash", "openrouter"),
        ModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro", "openrouter"),
        ModelInfo("deepseek/deepseek-chat", "DeepSeek V3", "openrouter"),
        ModelInfo("deepseek/deepseek-reasoner", "DeepSeek R1", "openrouter"),
        ModelInfo("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B", "openrouter"),
        ModelInfo("qwen/qwen-2.5-72b-instruct", "Qwen 2.5 72B", "openrouter"),
        ModelInfo("mistralai/mistral-large-latest", "Mistral Large", "openrouter"),
        ModelInfo("x-ai/grok-3", "Grok 3", "openrouter"),
        ModelInfo("moonshotai/kimi-k2", "Kimi K2", "openrouter"),

        // ── Kimi (api.kimi.ai) ──
        ModelInfo("kimi-k2", "Kimi K2", "kimi"),
        ModelInfo("kimi-k2-0711", "Kimi K2 0711", "kimi"),

        // ── Moonshot (api.moonshot.cn) ──
        ModelInfo("moonshot-v1-auto", "Moonshot V1 Auto", "moonshot"),
        ModelInfo("moonshot-v1-128k", "Moonshot V1 128K", "moonshot"),
        ModelInfo("moonshot-v1-32k", "Moonshot V1 32K", "moonshot"),
        ModelInfo("moonshot-v1-8k", "Moonshot V1 8K", "moonshot"),

        // ── HuggingFace (free tier — needs free HF token) ──
        ModelInfo("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B", "huggingface"),
        ModelInfo("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B", "huggingface"),
        ModelInfo("mistralai/Mistral-Small-24B-Instruct-2501", "Mistral Small 24B", "huggingface"),
        ModelInfo("google/gemma-2-27b-it", "Gemma 2 27B", "huggingface"),
        ModelInfo("microsoft/Phi-3.5-mini-instruct", "Phi-3.5 Mini", "huggingface"),
        ModelInfo("NousResearch/Hermes-3-Llama-3.1-8B", "Hermes 3 8B", "huggingface"),
        ModelInfo("deepseek-ai/DeepSeek-R1-0528", "DeepSeek R1", "huggingface"),

        // ── SambaNova (free tier — needs free API key) ──
        ModelInfo("Meta-Llama-3.3-70B-Instruct", "Llama 3.3 70B", "sambanova"),
        ModelInfo("DeepSeek-R1", "DeepSeek R1", "sambanova"),
        ModelInfo("Qwen2.5-72B-Instruct", "Qwen 2.5 72B", "sambanova"),
        ModelInfo("Meta-Llama-3.1-8B-Instruct", "Llama 3.1 8B", "sambanova"),

        // ── Cerebras (free tier — needs free API key, fastest inference) ──
        ModelInfo("llama-3.3-70b", "Llama 3.3 70B", "cerebras"),
        ModelInfo("llama-3.1-8b", "Llama 3.1 8B", "cerebras"),
        ModelInfo("qwen-2.5-32b", "Qwen 2.5 32B", "cerebras"),

        // ── Pollinations (FREE — no API key needed!) ──
        ModelInfo("openai", "GPT-4o Mini (free)", "pollinations"),
        ModelInfo("mistral", "Mistral Small (free)", "pollinations"),
        ModelInfo("qwen-coder", "Qwen Coder (free)", "pollinations"),
        ModelInfo("llama", "Llama 3.3 (free)", "pollinations"),
        ModelInfo("deepseek", "DeepSeek V3 (free)", "pollinations"),
        ModelInfo("gemini", "Gemini Flash (free)", "pollinations"),

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
