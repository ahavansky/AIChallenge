# AIChallenge

AIChallenge is a single-activity Android 12+ app built with Kotlin, Jetpack Compose, Material 3, Hilt, coroutines, direct Gemini REST integration, and HuggingFace Router calls for challenge/demo use.

## Architecture

The app uses unidirectional data flow. Compose renders immutable UI state from feature ViewModels; user actions flow back to the ViewModel; ViewModels update `StateFlow` after delegating LLM work to focused network abstractions. On submit, the home screen starts two Gemini requests in parallel: one with the user's `generationConfig`, and one baseline request without extra generation parameters. Agent Chat is a separate chat feature that talks to a `LlmAgent`, which owns Gemini prompt validation, chat request creation, token usage collection, HTTP execution, response parsing, retries, and user-facing error mapping. Context Agent is a separate context-strategy lab that compares Sliding Window, Sticky Facts, and Branching without summaries. Prompt Lab starts four prompting strategies and then asks Gemini to compare their outputs. Temperature Lab starts three requests with different `temperature` values and then asks Gemini to evaluate which setting fits which task types. HuggingFace Lab starts three HuggingFace model requests, records response time and token usage, and then asks the selected Gemini model to compare quality, speed, and resource usage.

Modules:

- `:app` - Android application entry point, `MainActivity`, Navigation 3 host, application-level Hilt bindings, and API-key wiring.
- `:core:designsystem` - Material 3 theme, dynamic color, typography, and reusable Compose controls used by features.
- `:core:mvvm` - Minimal marker contracts for state, events, and effects. No base classes are included because there is no shared behavior to enforce.
- `:core:network` - OkHttp Gemini agent/client and HuggingFace REST clients, kotlinx.serialization DTOs, `generationConfig` serialization, timeout setup, retry logic, and network/error mapping.
- `:core:utils` - Shared prompt normalization used by the feature and covered with unit tests.
- `:feature:common` - Shared feature-level UI models such as the response pane state and Gemini model options.
- `:feature:agent-chat` - Dedicated LLM agent chat screen, accumulated chat state, ViewModel, Compose UI, and tests.
- `:feature:context-agent` - Separate context-management strategy screen with Sliding Window, Sticky Facts, Branching, scenario comparison, JSON persistence, Compose UI, and tests.
- `:feature:home` - Prompt input, Gemini parameter controls, side-by-side response comparison, Home ViewModel, Compose UI, UI tests, and screenshot tests.
- `:feature:prompt-lab` - Four prompting-strategy comparison screen, ViewModel, UI state, Compose UI, and tests.
- `:feature:temperature-lab` - Three-temperature comparison screen, ViewModel, UI state, Compose UI, and tests.
- `:feature:huggingface-lab` - HuggingFace model benchmark screen, ViewModel, UI state, Compose UI, and tests.

There is intentionally no `:core:domain`: the first feature has no reusable business rules that justify a separate domain layer.

## API Keys

The app reads `GEMINI_API_KEY` from one of these dev-only sources:

- `local.properties`: `GEMINI_API_KEY=your_real_key`
- Gradle property: `./gradlew assembleDebug -PGEMINI_API_KEY=your_real_key`
- Environment variable: `GEMINI_API_KEY=your_real_key ./gradlew assembleDebug`

Do not commit real keys. `local.properties`, `.env*`, and `secrets.properties` are ignored.

This project calls Gemini directly from the Android app only because it is a demo/challenge build. For production, use a backend proxy or another server-side protection layer because secrets embedded in mobile apps can be extracted.

Model and endpoint:

- Model: `gemini-3.5-flash`
- REST endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent`

HuggingFace Lab reads a HuggingFace token from `HUGGINGFACE_API_KEY` or `HF_TOKEN`:

- `local.properties`: `HUGGINGFACE_API_KEY=your_hf_token` or `HF_TOKEN=your_hf_token`
- Gradle property: `./gradlew assembleDebug -PHUGGINGFACE_API_KEY=your_hf_token`
- Environment variable: `HF_TOKEN=your_hf_token ./gradlew assembleDebug`

The screen uses HuggingFace Router's OpenAI-compatible chat completions endpoint: `https://router.huggingface.co/v1/chat/completions`. HuggingFace free access is credit-based and provider/model availability can change, so a model may fail if the token has no credits or the provider route is unavailable.

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

Agent Chat is a separate screen for a simple LLM agent. The user picks a Gemini-backed agent before the first message:

- `gemini-3.5-flash` - default chat agent for this app.
- `gemini-2.5-flash` - balanced Gemini agent for everyday reasoning.
- `gemini-2.5-flash-lite` - lower-latency Gemini agent for simpler turns.
- `gemma-4-26b-a4b-it` - free Gemma 4 MoE agent for faster open-model reasoning.
- `gemma-4-31b-it` - free Gemma 4 dense agent for stronger open-model reasoning.

After the first valid user message, the selected agent is locked for that chat. The Clear chat action removes the accumulated conversation and unlocks agent selection again. Agent Chat keeps accumulated `user` and `model` turns in immutable UI state and sends the full successful conversation history to Gemini on every follow-up while it fits the active token budget. If the budget is exceeded, the Gemini agent applies a sliding window: it drops the oldest successful user/model turns from the request payload, keeps the latest user message, and caps `maxOutputTokens` to the remaining budget. Loading and error messages are shown in the chat, but they are not sent back as model context.

Agent Chat keeps token usage in a compact summary above the conversation history so it remains visible while the user scrolls through the chat. The panel shows the latest user request from Gemini `countTokens`, the full dialogue context sent to Gemini from `usageMetadata.promptTokenCount`, the model response from `usageMetadata.candidatesTokenCount`, the total request usage from `usageMetadata.totalTokenCount`, and the active total-token budget with remaining tokens. Users can set a custom total-token budget for the chat; leaving that field blank uses the selected model's documented limit. The current Gemini Flash agents use the documented `1,048,576` input token limit plus `65,536` output token limit. The Gemma 4 agents use `262,144` context tokens plus `128,000` max output tokens from the current Gemma 4 model cards. The screen also includes interactive token scenario buttons for a short dialog, a long dialog, and a dialog that exceeds the model limit. Each scenario starts an automated real-agent conversation through the selected Gemini model so users can watch token/cost growth and active-budget overflow; only the over-limit scenario exercises the sliding-window fallback. The long-dialog and over-limit scenarios temporarily switch to a compact demo budget and inject bounded memory blocks so the accumulated-history overflow path is reproducible regardless of the configured chat limit without making the current prompt exceed the scenario budget. The active request or scenario can be stopped from the screen. Tests use fake agents; the app path uses real LLM calls.

## Context Agent

Context Agent is a separate feature module and screen, not a mode inside Agent Chat. It reuses the shared `LlmAgent` transport but owns its own UI state, JSON history file, ViewModel, Compose screen, and tests. The screen includes the same model options as Agent Chat:

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
