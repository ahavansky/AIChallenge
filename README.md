# AIChallenge

AIChallenge is a single-activity Android 12+ app built with Kotlin, Jetpack Compose, Material 3, Hilt, coroutines, and a direct Gemini REST integration for challenge/demo use.

## Architecture

The app uses unidirectional data flow. Compose renders immutable UI state from `HomeViewModel`; user actions flow back to the ViewModel; the ViewModel calls a small Gemini client and updates `StateFlow`.

Modules:

- `:app` - Android application entry point, `MainActivity`, Navigation 3 host, application-level Hilt bindings, and API-key wiring.
- `:core:designsystem` - Material 3 theme, dynamic color, typography, and reusable Compose controls used by features.
- `:core:mvvm` - Minimal marker contracts for state, events, and effects. No base classes are included because there is no shared behavior to enforce.
- `:core:network` - OkHttp Gemini REST client, kotlinx.serialization DTOs, timeout setup, and network/error mapping.
- `:core:utils` - Shared prompt normalization used by the feature and covered with unit tests.
- `:feature:home` - Prompt input screen, `HomeViewModel`, UI state model, Compose UI, UI tests, and screenshot tests.

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

## Network Choice

The Gemini integration uses OkHttp plus kotlinx.serialization. OkHttp keeps the direct REST call small, explicit, and easy to test with a fake `Call.Factory`; adding Retrofit or Ktor would add more abstraction than this single endpoint needs.

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
