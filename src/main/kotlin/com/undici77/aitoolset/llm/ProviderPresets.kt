package com.undici77.aitoolset.llm

data class ProviderPreset(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val defaultModel: String,
    val keyRequired: Boolean,
)

object ProviderPresets {
    private val O = ProviderType.OPENAI_COMPAT

    val local = listOf(
        ProviderPreset("ollama", "Ollama", O, "http://localhost:11434/v1", "qwen2.5-coder:7b", false),
        ProviderPreset("omlx", "oMLX (Apple Silicon)", O, "http://localhost:8000/v1", "", false),
        ProviderPreset("lmstudio", "LM Studio", O, "http://localhost:1234/v1", "", false),
        ProviderPreset("llamacpp", "llama.cpp server", O, "http://localhost:8080/v1", "", false),
        ProviderPreset("mlxlm", "mlx_lm.server", O, "http://localhost:8080/v1", "", false),
        ProviderPreset("vllm", "vLLM", O, "http://localhost:8000/v1", "", false),
    )

    /** Shown after a separator: any server that speaks one of the two protocols. */
    val generic = listOf(
        ProviderPreset("openai-compatible", "Generic OpenAI-compatible", O, "http://localhost:8000/v1", "", false),
        ProviderPreset("anthropic-compatible", "Generic Anthropic-compatible", ProviderType.ANTHROPIC, "http://localhost:8000", "", false),
    )

    val all = local + generic
}
