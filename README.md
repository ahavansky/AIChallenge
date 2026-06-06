# AIChallenge

AIChallenge is a single-activity Android 12+ app built with Kotlin, Jetpack Compose, Material 3, Hilt, coroutines, direct Gemini REST integration, and HuggingFace Router calls for challenge/demo use.

## Architecture

The app uses unidirectional data flow. Compose renders immutable UI state from feature ViewModels; user actions flow back to the ViewModel; the ViewModel calls small LLM clients and updates `StateFlow`. On submit, the home screen starts two Gemini requests in parallel: one with the user's `generationConfig`, and one baseline request without extra generation parameters. Prompt Lab starts four prompting strategies and then asks Gemini to compare their outputs. Temperature Lab starts three requests with different `temperature` values and then asks Gemini to evaluate which setting fits which task types. HuggingFace Lab starts three HuggingFace model requests, records response time and token usage, and then asks the selected Gemini model to compare quality, speed, and resource usage.

Modules:

- `:app` - Android application entry point, `MainActivity`, Navigation 3 host, application-level Hilt bindings, and API-key wiring.
- `:core:designsystem` - Material 3 theme, dynamic color, typography, and reusable Compose controls used by features.
- `:core:mvvm` - Minimal marker contracts for state, events, and effects. No base classes are included because there is no shared behavior to enforce.
- `:core:network` - OkHttp Gemini and HuggingFace REST clients, kotlinx.serialization DTOs, `generationConfig` serialization, timeout setup, retry logic, and network/error mapping.
- `:core:utils` - Shared prompt normalization used by the feature and covered with unit tests.
- `:feature:home` - Prompt input, Gemini parameter controls, side-by-side response comparison, Prompt Lab, Temperature Lab, HuggingFace Lab, feature ViewModels, UI state models, Compose UI, UI tests, and screenshot tests.

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

The Gemini and HuggingFace integrations use OkHttp plus kotlinx.serialization. OkHttp keeps the direct REST calls small, explicit, and easy to test with a fake `Call.Factory`; adding Retrofit or Ktor would add more abstraction than these endpoints need.

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
