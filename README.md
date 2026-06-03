# AIChallenge

AIChallenge is a single-activity Android 12+ app built with Kotlin, Jetpack Compose, Material 3, Hilt, coroutines, and a direct Gemini REST integration for challenge/demo use.

## Architecture

The app uses unidirectional data flow. Compose renders immutable UI state from feature ViewModels; user actions flow back to the ViewModel; the ViewModel calls a small Gemini client and updates `StateFlow`. On submit, the home screen starts two Gemini requests in parallel: one with the user's `generationConfig`, and one baseline request without extra generation parameters. Prompt Lab starts four prompting strategies and then asks Gemini to compare their outputs.

Modules:

- `:app` - Android application entry point, `MainActivity`, Navigation 3 host, application-level Hilt bindings, and API-key wiring.
- `:core:designsystem` - Material 3 theme, dynamic color, typography, and reusable Compose controls used by features.
- `:core:mvvm` - Minimal marker contracts for state, events, and effects. No base classes are included because there is no shared behavior to enforce.
- `:core:network` - OkHttp Gemini REST client, kotlinx.serialization DTOs, `generationConfig` serialization, timeout setup, and network/error mapping.
- `:core:utils` - Shared prompt normalization used by the feature and covered with unit tests.
- `:feature:home` - Prompt input, Gemini parameter controls, side-by-side response comparison, Prompt Lab, feature ViewModels, UI state models, Compose UI, UI tests, and screenshot tests.

There is intentionally no `:core:domain`: the first feature has no reusable business rules that justify a separate domain layer.

## Gemini API Key

The app reads `GEMINI_API_KEY` from one of these dev-only sources:

- `local.properties`: `GEMINI_API_KEY=your_real_key`
- Gradle property: `./gradlew assembleDebug -PGEMINI_API_KEY=your_real_key`
- Environment variable: `GEMINI_API_KEY=your_real_key ./gradlew assembleDebug`

Do not commit real keys. `local.properties`, `.env*`, and `secrets.properties` are ignored.

This project calls Gemini directly from the Android app only because it is a demo/challenge build. For production, use a backend proxy or another server-side protection layer because secrets embedded in mobile apps can be extracted.

Model and endpoint:

- Model: `gemini-3.5-flash`
- REST endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent`

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

## Network Choice

The Gemini integration uses OkHttp plus kotlinx.serialization. OkHttp keeps the direct REST call small, explicit, and easy to test with a fake `Call.Factory`; adding Retrofit or Ktor would add more abstraction than this single endpoint needs.

The REST client retries temporary Gemini HTTP failures up to 3 attempts for `429`, `500`, `502`, `503`, and `504`. Validation/configuration errors such as `400 INVALID_ARGUMENT` are not retried.

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
