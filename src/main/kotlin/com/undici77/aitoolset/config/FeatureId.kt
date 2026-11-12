package com.undici77.aitoolset.config

enum class FeatureId(val key: String, val fileName: String, val title: String, val usesModel: Boolean) {
    COMMIT_MESSAGE("commit_message", "commit-message", "Commit message", true),
    CHAT("chat", "chat", "Chat", true),
    ASK_SELECTION("ask_selection", "ask-selection", "Ask about selection", false),
    EXPLAIN_COMMIT("explain_commit", "explain-commit", "Explain commit", true);

    companion object {
        fun fromKey(key: String): FeatureId? = entries.firstOrNull { it.key == key }
    }
}
