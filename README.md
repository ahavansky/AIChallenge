# AIChallenge

AIChallenge is a single-activity Android 12+ app built with Kotlin, Jetpack Compose, Material 3, Hilt, coroutines, direct Gemini and DeepSeek REST integration, and HuggingFace Router calls for challenge/demo use.

## Architecture

The app uses unidirectional data flow. Compose renders immutable UI state from feature ViewModels; user actions flow back to the ViewModel; ViewModels update `StateFlow` after delegating LLM work to focused network abstractions. On submit, the home screen starts two Gemini requests in parallel: one with the user's `generationConfig`, and one baseline request without extra generation parameters. Agent Chat is a separate chat feature that talks to a routed `LlmAgent`, which owns provider selection, prompt validation, chat request creation, HTTP execution, response parsing, retries, and user-facing error mapping. Context Agent is a separate context-strategy lab that compares Sliding Window, Sticky Facts, and Branching without summaries. Prompt Lab starts four prompting strategies and then asks Gemini to compare their outputs. Temperature Lab starts three requests with different `temperature` values and then asks Gemini to evaluate which setting fits which task types. HuggingFace Lab starts three HuggingFace model requests, records response time and token usage, and then asks the selected Gemini model to compare quality, speed, and resource usage.

Modules:

- `:app` - Android application entry point, `MainActivity`, Navigation 3 host, application-level Hilt bindings, and API-key wiring.
- `:core:designsystem` - Material 3 theme, dynamic color, typography, and reusable Compose controls used by features.
- `:core:mvvm` - Minimal marker contracts for state, events, and effects. No base classes are included because there is no shared behavior to enforce.
- `:core:network` - OkHttp Gemini, DeepSeek, HuggingFace, and MCP discovery/tool-call clients, kotlinx.serialization DTOs, `generationConfig` serialization, timeout setup, retry logic, and network/error mapping.
- `:core:utils` - Shared prompt normalization used by the feature and covered with unit tests.
- `:feature:common` - Shared feature-level UI models such as the response pane state and Gemini model options.
- `:feature:agent-chat` - Dedicated LLM agent chat screen, accumulated chat state, ViewModel, Compose UI, and tests.
- `:feature:context-agent` - Separate context-management strategy screen with Sliding Window, Sticky Facts, Branching, scenario comparison, JSON persistence, Compose UI, and tests.
- `:feature:home` - Prompt input, Gemini parameter controls, side-by-side response comparison, Home ViewModel, Compose UI, UI tests, and screenshot tests.
- `:feature:prompt-lab` - Four prompting-strategy comparison screen, ViewModel, UI state, Compose UI, and tests.
- `:feature:temperature-lab` - Three-temperature comparison screen, ViewModel, UI state, Compose UI, and tests.
- `:feature:huggingface-lab` - HuggingFace model benchmark screen, ViewModel, UI state, Compose UI, and tests.
- `:mcp:github-server` - Standalone JVM MCP HTTP server that exposes a read-only GitHub repository summary tool backed by the live GitHub REST API.

There is intentionally no `:core:domain`: the first feature has no reusable business rules that justify a separate domain layer.

## API Keys

The app reads `GEMINI_API_KEY` from one of these dev-only sources:

- `local.properties`: `GEMINI_API_KEY=your_real_key`
- Gradle property: `./gradlew assembleDebug -PGEMINI_API_KEY=your_real_key`
- Environment variable: `GEMINI_API_KEY=your_real_key ./gradlew assembleDebug`

Do not commit real keys. `local.properties`, `.env*`, and `secrets.properties` are ignored.

This project calls Gemini directly from the Android app only because it is a demo/challenge build. For production, use a backend proxy or another server-side protection layer because secrets embedded in mobile apps can be extracted.

Default model and endpoint:

- Default model: `gemini-3.5-flash`
- REST endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent`
- Screens with a model selector pass the selected model name into the same `/models/{model}:generateContent` route.

HuggingFace Lab reads a HuggingFace token from `HUGGINGFACE_API_KEY` or `HF_TOKEN`:

- `local.properties`: `HUGGINGFACE_API_KEY=your_hf_token` or `HF_TOKEN=your_hf_token`
- Gradle property: `./gradlew assembleDebug -PHUGGINGFACE_API_KEY=your_hf_token`
- Environment variable: `HF_TOKEN=your_hf_token ./gradlew assembleDebug`

The screen uses HuggingFace Router's OpenAI-compatible chat completions endpoint: `https://router.huggingface.co/v1/chat/completions`. HuggingFace free access is credit-based and provider/model availability can change, so a model may fail if the token has no credits or the provider route is unavailable.

Agent Chat reads a DeepSeek API key from `DEEPSEEK_API_KEY` when a DeepSeek model is selected:

- `local.properties`: `DEEPSEEK_API_KEY=your_deepseek_key`
- Gradle property: `./gradlew assembleDebug -PDEEPSEEK_API_KEY=your_deepseek_key`
- Environment variable: `DEEPSEEK_API_KEY=your_deepseek_key ./gradlew assembleDebug`

DeepSeek Agent Chat models use DeepSeek's OpenAI-compatible chat completions endpoint: `https://api.deepseek.com/chat/completions`. They are not sent through Gemini `generateContent`.

Agent Chat can call a standalone GitHub MCP server. Start it from the project root:

```bash
rtk ./gradlew :mcp:github-server:run
```

`MCP_SERVER_URL` can be set through `local.properties`, a Gradle property, or an environment variable. If it is omitted, the Android app defaults to the emulator host-loopback URL:

```properties
MCP_SERVER_URL=http://10.0.2.2:8765/mcp
```

Do not use `http://localhost:8765/mcp` in the Android emulator config: inside the emulator, `localhost` points to the emulator itself, while `10.0.2.2` points to the host machine running the MCP server. For local emulator demos, the app normalizes `localhost` and `127.0.0.1` MCP URLs to `10.0.2.2` at runtime. The app permits cleartext HTTP only for local MCP development hosts (`10.0.2.2`, `10.0.3.2`, `127.0.0.1`, and `localhost`).

The MCP server exposes `github_repository_summary`, validates `owner` and `repo`, and calls the live GitHub REST API route `GET https://api.github.com/repos/{owner}/{repo}`. The optional `GITHUB_TOKEN` environment variable is read only by the server process to raise GitHub rate limits; the Android app does not receive this token. `MCP_FETCH_SERVER_URL` remains accepted as a legacy fallback.

## Gemini Parameter Comparison

The home screen exposes Gemini `generationConfig` controls for `responseMimeType`, `responseSchema`, `maxOutputTokens`, `stopSequences`, `temperature`, `topP`, `topK`, `candidateCount`, `presencePenalty`, and `frequencyPenalty`. Numeric controls use sliders; each control includes a detailed UI explanation with boundary values:

- `responseMimeType`: supported values are `text/plain`, `application/json`, and `text/x.enum`; the default is `application/json` so the schema example is valid immediately.
- `responseSchema`: prefilled with an editable Gemini/OpenAPI schema example using REST type values like `OBJECT`, `STRING`, and `ARRAY`; use only with `application/json` or `text/x.enum`.
- `maxOutputTokens`: slider range is `1..4096`; the model maximum is model-specific.
- `stopSequences`: blank or up to 5 newline-separated stop strings. Example: `END` or `###` on separate lines; if the model emits that marker, generation stops before it.
- `temperature`: slider range is `0.0..2.0`; model-supported range/default can vary.
- `topP`: slider range is `0.0..1.0`.
- `topK`: slider range is `1..40`; model support/default can vary.
- `candidateCount`: slider range is `1..8`; multiple candidates are model-dependent and increase output-token usage.
- `presencePenalty`: shown for reference, but not sent for `gemini-3.5-flash` because the API returns `Penalty is not enabled for this model`; future supported-model range is `-2.0 <= value < 2.0`.
- `frequencyPenalty`: shown for reference, but not sent for `gemini-3.5-flash` because the API returns `Penalty is not enabled for this model`; future supported-model range is `-2.0 <= value < 2.0`.

Submitting a prompt sends two concurrent requests:

- Configured request: includes the user's `generationConfig`.
- Baseline request: omits `generationConfig`.

The response area shows both outputs in separate panes so the user can compare how generation parameters change the model response. If `candidateCount` returns multiple candidates, the network parser labels and joins the candidates for display.

## Agent Chat

Agent Chat is a separate screen for a personalized stateful LLM agent. It keeps explicit context sources separate: personalization is stored in `agent_chat_profiles.json` as editable user profiles and sent as Gemini `systemInstruction`, hard/soft invariants are stored in a separate user-editable `agent_chat_invariants.md` file, short-term memory is selected from successful chat messages in the JSON history, working memory is an editable `TaskContext` with goal/stage/plan/constraints/open questions/results, formal task state is stored as a finite state machine in `agent_chat_history.json`, and long-term memory is a user-editable `agent_chat_memory.md` file with durable facts, decisions, and reusable knowledge.

Agent Chat has a compact header model selector. The selected model is saved in `agent_chat_history.json` and used for every pipeline, branch, repair, and profile-comparison request:

- `gemini-3.5-flash` - default project model.
- `gemini-2.5-flash` - balanced Gemini model for everyday reasoning and implementation tasks.
- `gemini-2.5-flash-lite` - lower-latency Gemini model for simpler or more iterative tasks.
- `deepseek-v4-flash` - lower-latency DeepSeek model for iterative agent chat tasks.
- `deepseek-v4-pro` - higher-capability DeepSeek model for more demanding agent chat tasks.
- `gemma-4-31b-it` - free Gemma 4 dense model for stronger open-model reasoning.

`Run task` is the main Agent Chat submit path and starts a formal pipeline owned by app code: `planning -> execution -> validation -> done`. Each stage begins with five parallel specialist branches for intent, constraints, context, solution strategy, and review; an orchestrator step then synthesizes the specialist artifacts into the stage artifact. The app gates transitions in code: execution cannot start until the user approves the task spec, finalization cannot start until validation reports `PASS` and the user accepts it, and invalid jumps are rejected with local errors. Plan revision returns to planning, execution revision returns to execution, and validation outcomes are stored explicitly as `PASS`, `NEEDS_REVISION`, `BLOCKED`, or `UNKNOWN`. The task can be paused on any running step, persisted, and continued later without replaying completed artifacts; branch retry reruns only failed specialist branches. If the app restores a task that was running during process death, it reopens it as paused. Before each request, the prompt builder applies instruction priority rules, adds the active profile to the system instruction, includes invariants before formal task state and editable `TaskContext`, applies simple budget limits, appends selected memory layers, and finally sends the current user task, branch prompt, or pipeline step prompt. Hard invariant conflicts in user requests are refused locally before Gemini is called. Model outputs are checked before they are stored as typed artifacts; a hard invariant violation gets one repair request, and a repeated violation fails the current task step. The screen shows the current stage, step, branch status, waiting reason, validation outcome, derived expected action, saved artifacts, concrete context contents by source, and which sources were used by the last request. Loading and error messages are shown in the chat, but they are not sent back as model context.

`Use GitHub MCP` is a local Agent Chat action. Enter a repository as `owner/repo`, for example `square/okhttp`; the app calls MCP `tools/call` for `github_repository_summary`, receives live GitHub metadata, then sends that tool result to the selected LLM as untrusted external data. The final chat message shows both the MCP tool result and the agent answer derived from it. The existing MCP client still supports `tools/list` discovery for diagnostics.

## Context Agent

Context Agent is a separate feature module and screen, not a mode inside Agent Chat. It reuses the shared `LlmAgent` transport but owns its own UI state, JSON history file, ViewModel, Compose screen, and tests. The screen includes its own model options:

- `gemini-3.5-flash`
- `gemini-2.5-flash`
- `gemini-2.5-flash-lite`
- `gemma-4-26b-a4b-it`
- `gemma-4-31b-it`

Normal sends use the selected strategy:

- Sliding Window stores and sends only the latest `8` successful `user`/`model` messages. Older visible chat messages are discarded from that strategy state.
- Sticky Facts updates a separate key-value facts block after every user message, then sends `facts + latest 8 messages`. Facts capture durable items such as goals, constraints, preferences, decisions, deadlines, budgets, and roles. They are not summaries.
- Branching lets the user save a checkpoint, create Branch A and Branch B from that checkpoint, switch between branches, and continue each branch independently. The active branch request includes checkpoint messages plus that branch's messages.

Before sending a strategy request, Context Agent calls Gemini `countTokens` for the full payload and the selected strategy payload. The UI shows prompt tokens before and after strategy processing, saved tokens, saved percent, stored message count, request message count, dropped message count, facts count, and active branch when relevant. Context Agent passes each model's input window as the request token limit and sets an explicit output budget for generated answers. This prevents Gemma requests from asking for extremely large `maxOutputTokens` values when the prompt is small.

The Run scenario action uses the same 10-15 turn requirements-gathering scenario for all strategies. It asks the model to produce a final requirements brief with Sliding Window, Sticky Facts, Branch A, and Branch B, then shows answer quality notes, stability notes, prompt-token use, and user-convenience tradeoffs for each run. Gemini models use a larger scenario output budget to avoid truncation from thinking tokens; Gemma models use a smaller budget because long-output Gemma requests can return provider-side 500 errors. If `gemma-4-31b-it` still returns HTTP 500 on a small request, Context Agent shows it as provider/model availability instead of a context-management failure.

## Prompt Lab

Prompt Lab includes a model selector for the main current free-tier Gemini Developer API text models:

- `gemini-2.5-flash` - stable free-tier model for balanced quality, speed, and everyday reasoning tasks.
- `gemini-2.5-flash-lite` - stable free-tier model tuned for lower latency and simpler high-volume tasks.

Older free-tier variants such as `gemini-2.0-flash-lite` are intentionally not listed because Google marks them as shut down as of June 1, 2026.

The Prompt Lab screen sends one task through four methods and displays all four outputs:

- Direct prompt: sends the task as-is.
- Step-by-step: prepends `решай пошагово`.
- Generated prompt: first asks Gemini to create a prompt for solving the task, then sends that generated prompt.
- Expert group: asks an analyst, engineer, and critic to solve the task separately.

After the four outputs complete, the app sends a final Gemini request that compares whether the answers differ and which method produced the most accurate result.

## Temperature Lab

Temperature Lab compares the same task across three `temperature` values. The screen includes the same free-tier Gemini model selector as Prompt Lab and three sliders in the `0.0..2.0` range:

- Temperature A defaults to `0.2` for precise, stable answers.
- Temperature B defaults to `0.7` for balanced everyday answers.
- Temperature C defaults to `1.4` for more divergent and creative answers.

Submitting a task sends three concurrent Gemini requests with only `generationConfig.temperature` changed. After all three outputs finish, the app sends those outputs back to Gemini and asks which temperature is best suited for which task types and which setting fits the current task.

## HuggingFace Lab

HuggingFace Lab benchmarks three preset HuggingFace chat/provider routes. The model ids include a provider suffix because the HuggingFace Router can reject bare model ids with `model_not_supported` when no enabled provider supports that route:

- Weak preset: `openai/gpt-oss-20b:groq`
- Medium preset: `openai/gpt-oss-120b:groq`
- Strong preset: `openai/gpt-oss-120b:cerebras`

Submitting a task sends three concurrent HuggingFace Router chat completion requests with the same prompt, `max_tokens=1024`, and `reasoning_effort=low`. The lower reasoning effort keeps `gpt-oss` from spending the whole output budget on hidden reasoning before producing visible `message.content`. HuggingFace requests use a longer 90-second timeout because provider-backed large models can take longer than Gemini. The screen displays each answer plus:

- Response time in milliseconds, measured around the individual network request.
- Completion throughput in tokens per second, calculated from `completion_tokens` and response time.
- Attempt and retry counts for the successful provider response.
- Provider `finish_reason`, so the user can see whether the model stopped normally or hit the output limit.
- Token usage from the provider response `usage`: `prompt_tokens`, `completion_tokens`, `total_tokens`, `visible_output_tokens`, and `reasoning_tokens` when the provider returns reasoning details.

After all successful and failed HuggingFace results are collected, the selected Gemini evaluator model receives the answers and metrics. Gemini scores accuracy, instruction following, clarity, hallucination risk, speed, stability, and resource usage, then recommends the best model for the current task.

## Network Choice

The Gemini and HuggingFace integrations use OkHttp plus kotlinx.serialization. Agent Chat and Context Agent go through `LlmAgent`; `GeminiTextClient` remains as a small compatibility adapter for Home and lab screens that already share the same Gemini transport. `LlmAgent.countTokens` uses Gemini `:countTokens` so agent features can compare prompt size before generation. OkHttp keeps the direct REST calls small, explicit, and easy to test with a fake `Call.Factory`; adding Retrofit or Ktor would add more abstraction than these endpoints need.

The REST clients retry temporary LLM HTTP failures up to 3 attempts for `429`, `500`, `502`, `503`, and `504`. Validation/configuration errors such as `400 INVALID_ARGUMENT` are not retried.

## Commands

Build:

```bash
./gradlew assembleDebug
```

Unit tests:

```bash
./gradlew testDebugUnitTest
```

Coverage:

```bash
./gradlew :koverXmlReport :koverVerify
```

Kover excludes Compose screen/route classes that are covered by Compose UI and screenshot tests; ViewModels, contracts, and network code remain covered by unit tests.

Compose UI tests:

```bash
./gradlew connectedDebugAndroidTest
```

Screenshot tests:

```bash
./gradlew :feature:home:updateDebugScreenshotTest
./gradlew :feature:home:validateDebugScreenshotTest
```

Lint/format checks:

```bash
./gradlew ktlintCheck lintDebug
```

## Screenshot Testing

The project uses Android's official Compose Preview Screenshot Testing plugin (`com.android.compose.screenshot`). It matches the AGP 9 template, runs host-side, and reuses Compose previews through the `screenshotTest` source set.

## Environment

- JDK 17+
- Android SDK with compile SDK 36
- Android Gradle Plugin 9.0.1
- Kotlin 2.3.20
- Android Studio/CLI that supports AGP 9 and Compose Preview Screenshot Testing
