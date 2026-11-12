# Changelog

## [Unreleased]

## [1.0.0] - 2026-11-12

### Added

- Chat: clearer conversation layout: your messages are rounded, tinted bubbles on the right under "You"; answers are full-width, neutral-tinted bubbles on the left under the model name, with the stats below.
- Chat: Markdown tables (with column alignment), block quotes, thin horizontal rules, nested lists (also while streaming), strikethrough and sized headings.
- Chat: a note under each message with attached files shows what was sent (e.g. `3 files · ~2.1k tokens · 1 omitted (too large)`); hover for the file names.
- Chat: "Enter to Send" option in the AI Chat options (⋮) menu: Enter sends, Shift+Enter inserts a new line. Off by default (Ctrl/Cmd+Enter sends, Enter inserts a new line); Ctrl/Cmd+Enter always sends.

### Changed

- Chat: the model selector is disabled while an answer is running.
- Generate commit message: status bar progress with the current step (reading changes, waiting for the model, thinking, writing) and a cancel button; while streaming, the field no longer shows code fences, a "Commit message:" label or a leading quote.
- Explain Commit: the chat shows "Reading commit…" at once and git errors appear in the chat with Retry; if the chat is busy, it asks to stop the current answer instead of doing nothing.
- Errors: HTTP errors show the server's own message (e.g. `HTTP 404: model 'x' not found`) instead of the raw JSON body; non-JSON bodies are shown as before.

## [0.1.1] - 2026-11-10

### Fixed

- Generate commit message: no more `fatal: empty string is not a valid pathspec` when a checked change is the repo root itself.
- Chat: no dark caret drawn on the first letter of read-only messages.
- Chat: `<think>…</think>` blocks inside the answer are moved to the reasoning section instead of shown raw (also in the commit message field while streaming).
- Chat: the answer is rendered as Markdown while it streams, with no jump to the final layout at the end; updates are batched (~20 fps) to avoid flicker.
- Chat: text wraps to the tool window width on resize instead of being clipped; code block scrollbars no longer cover the last line.
- Chat: numbered lists keep the model's numbering when items are separated by blank lines.
- Chat: Retry replaces the failed exchange instead of leaving an orphan error; New Chat during streaming no longer leaks the partial answer into the new conversation.
- Chat: long HTTP errors are wrapped and shortened, with a Details link.

### Added

- Features: "Reasoning effort" (sent as `reasoning_effort`; Anthropic: `output_config.effort`). Commit message defaults to `low`, others to the server default.
- Providers: "Extra request body" JSON merged into every request (e.g. `{"chat_template_kwargs": {"enable_thinking": false}}` for Qwen on vLLM/llama.cpp).
- A reply that contains only reasoning (token budget spent thinking) now fails with a clear message instead of an empty result.
- Chat: a status line from the moment of sending: preparing, connecting, waiting for the model (with elapsed time), thinking, writing.
- Chat: the model's reasoning is shown in a collapsed "Show reasoning" section.
- Chat: footer with model, time, tokens and tokens/s; "Stopped" and "Cut off" (max output tokens) states, with a Continue link.
- Chat: Open Settings link on configuration, connection and authentication errors.
- Settings entry in the AI Chat tool window options (⋮) menu and title bar.

### Changed

- Features: max output tokens and temperature are left to the server when empty (Anthropic falls back to 8192, as the API requires a value); context limit has a built-in default shown as placeholder. Old fixed values (e.g. 400 tokens) are dropped.
- Providers: the default model is auto-filled from the server (first model listed) when empty, after adding a keyless provider or typing a key.
- Settings: easy mode by default (providers only); "Advanced setup" checkbox reveals the Features tab.
- A newly added provider becomes the default for every feature that has none.
- Commit prompt rewritten for strict Conventional Commits (examples, 50-char subject, breaking changes).
- Icons redrawn in the New UI style: gray 1px outlines with blue accent, light/dark variants, a monochrome tool window icon with 16×16 and 20×20 sizes, and a gradient plugin icon with a dark variant.

## [0.1.0] - 2026-11-07

### Added

- Generate commit message from the checked changes (streams, cancellable, optional hint).
- AI Chat tool window with file/selection context, drag and drop, Copy / Insert at Caret.
- Add to AI Chat (Project view, editor tab), Ask AI about selection (editor), Explain commit (Git log).
- Providers: Anthropic Messages and OpenAI-compatible (Ollama, oMLX, LM Studio, llama.cpp, vLLM, OpenRouter, Gemini, Mistral, DeepSeek, custom).
- Global settings plus per-project overrides in `.ai-toolset/`.

### Known API notes (Plugin Verifier, CLion 2025.3: compatible)

- 4 deprecated usages, all `ToolWindowFactory.isApplicable(Project)` and `isDoNotActivateOnStart()` in `ChatToolWindowFactory`.
- 6 experimental usages, all `ToolWindowFactory.getAnchor()`, `getIcon()` and `manage(...)` in `ChatToolWindowFactory`.

### Build versions

- Kotlin 2.2.21, IntelliJ Platform Gradle Plugin 2.20.0, Gradle 9.9.0, JDK 21, target CLion 2025.3 (since-build 253).

[Unreleased]: https://github.com/undici77/local-ai-toolset/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/undici77/local-ai-toolset/releases/tag/v1.0.0
