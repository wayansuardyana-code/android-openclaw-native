package com.openclaw.android.ai

/**
 * Tool definition for LLM function calling.
 * Compatible with both Anthropic and OpenAI tool schemas.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)
