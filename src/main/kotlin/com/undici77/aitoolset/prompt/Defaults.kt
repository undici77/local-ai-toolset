package com.undici77.aitoolset.prompt

import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.FeatureState

object Defaults {
    /** Used when the reasoning effort was never set. Commit messages do not need deep thinking. */
    fun reasoningEffort(id: FeatureId): String = if (id == FeatureId.COMMIT_MESSAGE) "low" else ""

    /** Used when the context limit is not set. Output tokens are never defaulted: the server decides. */
    fun contextChars(id: FeatureId): Int = when (id) {
        FeatureId.COMMIT_MESSAGE -> 60_000
        FeatureId.CHAT -> 120_000
        FeatureId.ASK_SELECTION -> 120_000
        FeatureId.EXPLAIN_COMMIT -> 60_000
    }

    fun feature(id: FeatureId): FeatureState = FeatureState().also { f ->
        f.featureId = id.key
        when (id) {
            FeatureId.COMMIT_MESSAGE -> {
                                f.systemPrompt = COMMIT_SYSTEM; f.userTemplate = COMMIT_USER
            }
            FeatureId.CHAT -> {
                                f.systemPrompt = CHAT_SYSTEM; f.userTemplate = ""
            }
            FeatureId.ASK_SELECTION -> {
                f.userTemplate = "Explain this code and point out any bugs:"
            }
            FeatureId.EXPLAIN_COMMIT -> {
                                f.systemPrompt = EXPLAIN_SYSTEM; f.userTemplate = "{commit}"
            }
        }
    }

    private val COMMIT_SYSTEM = """
Generate a commit message from a diff that strictly follows the Conventional Commits specification.

Output rules:
- Output ONLY the raw commit message, exactly as it should be passed to `git commit`.
- Do NOT wrap it in a code block, backticks, quotes, or any other Markdown.
- Do NOT add explanations, comments, greetings, or labels such as "Output:".
- No leading or trailing blank lines, no trailing whitespace.

Input:
You will receive the branch, recent commits (for scope naming only), an optional author's hint, the changed files and a diff.
If an author's hint is given, it has high priority for the type, scope and wording.

Commit message format:
<type>[(scope)][!]: <description>

[body]

[footer]

Rules:
- type: one of feat, fix, docs, style, refactor, test, chore (lower-case)
- scope (optional): subsystem or component (auth, ui, cache, ...); omit if unclear
- description:
  * starts with a lower-case, imperative present-tense verb
  * summarizes what changed, not how
  * at most 50 characters, no trailing period
- body (optional): separated from the description by one blank line; free-form text, or a bullet list ("- " items) when there are multiple points
- footer (optional): separated from the body by one blank line
  * breaking changes: add "!" after the type/scope AND/OR a line starting with "BREAKING CHANGE: "
  * issue refs: Fixes #123, Refs #456, etc.
- If the diff spans multiple unrelated changes, summarize them under the most significant type.

Examples:

Diff:
--- src/validator.c
+++ src/validator.c
@@ -105,7 +105,7 @@
-    if (strlen(token) == 0) {
+    if (token == NULL || strlen(token) == 0) {
         return false;
     }

Output:
fix(auth): add null-check to token validator

Diff:
--- src/cache/manager.c
+++ src/cache/manager.c
@@ -42,6 +42,11 @@
 void initCache() {
     cache_size = DEFAULT_SIZE;
+    cache_ttl = DEFAULT_TTL;
+    cache_policy = LRU;
+
+    initHashTable(&cache_table);
+    startCleanupThread();
 }

Output:
feat(cache): implement configurable caching system

- add time-to-live (TTL) for cached items
- introduce LRU eviction policy
- start automatic cleanup thread

Diff:
--- src/api.c
+++ src/api.c
@@ -87,4 +87,4 @@
-void processRequest(Request* req) {
-    sendResponse(formatResponse(req->data));
+void processRequest(Request* req, Callback cb) {
+    cb(createResponse(req->data));
 }

Output:
feat(api)!: use callback for response handling

BREAKING CHANGE: `processRequest()` now requires a `Callback` argument.

Task:
Reply with the commit message only, as plain text, following the rules above.
""".trimIndent()

    private val COMMIT_USER = """
Branch: {branch}

Recent commits:
{recent_commits}
{?hint:
Author's hint (high priority): {hint}
}
Changed files:
{files}

Diff:
{diff}
""".trimIndent()

    private val CHAT_SYSTEM = """
You are a concise senior C/C++ engineer helping inside the CLion IDE.
Answer directly and briefly. Use fenced code blocks with a language tag for code.
When context files are provided, refer to them by path and line numbers.
{?context:

# Context
{context}}
""".trimIndent()

    private val EXPLAIN_SYSTEM = """
Explain a git commit to a developer. Structure:
1. Intent: one or two sentences.
2. Key changes: short bullets, referencing files.
3. Risks or things to double-check, only if any.
Be concise.
""".trimIndent()
}
