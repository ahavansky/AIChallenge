import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.akhavanskii.aichallenge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.akhavanskii.aichallenge"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    val localProperties =
        Properties().apply {
            val localFile = rootProject.file("local.properties")
            if (localFile.exists()) {
                localFile.inputStream().use { input -> load(input) }
            }
        }
    val geminiApiKey =
        providers
            .gradleProperty("GEMINI_API_KEY")
            .orElse(providers.environmentVariable("GEMINI_API_KEY"))
            .orElse(localProperties.getProperty("GEMINI_API_KEY") ?: "")
            .get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    val huggingFaceApiKey =
        providers
            .gradleProperty("HUGGINGFACE_API_KEY")
            .orElse(providers.gradleProperty("HF_TOKEN"))
            .orElse(providers.environmentVariable("HUGGINGFACE_API_KEY"))
            .orElse(providers.environmentVariable("HF_TOKEN"))
            .orElse(localProperties.getProperty("HUGGINGFACE_API_KEY") ?: localProperties.getProperty("HF_TOKEN") ?: "")
            .get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    val deepSeekApiKey =
        providers
            .gradleProperty("DEEPSEEK_API_KEY")
            .orElse(providers.environmentVariable("DEEPSEEK_API_KEY"))
            .orElse(localProperties.getProperty("DEEPSEEK_API_KEY") ?: "")
            .get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    val defaultMcpServerUrl = "http://10.0.2.2:8765/mcp"
    val localMcpServerUrl =
        localProperties
            .getProperty("MCP_SERVER_URL")
            ?.takeIf { it.isNotBlank() }
            ?: localProperties
                .getProperty("MCP_FETCH_SERVER_URL")
                ?.takeIf { it.isNotBlank() }
    val mcpServerUrl =
        providers
            .gradleProperty("MCP_SERVER_URL")
            .orElse(providers.environmentVariable("MCP_SERVER_URL"))
            .orElse(providers.gradleProperty("MCP_FETCH_SERVER_URL"))
            .orElse(providers.environmentVariable("MCP_FETCH_SERVER_URL"))
            .orElse(localMcpServerUrl ?: defaultMcpServerUrl)
            .get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    defaultConfig {
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "HUGGINGFACE_API_KEY", "\"$huggingFaceApiKey\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
        buildConfigField("String", "MCP_SERVER_URL", "\"$mcpServerUrl\"")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":feature:agent-chat"))
    implementation(project(":feature:context-agent"))
    implementation(project(":feature:home"))
    implementation(project(":feature:huggingface-lab"))
    implementation(project(":feature:prompt-lab"))
    implementation(project(":feature:temperature-lab"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    implementation(libs.timber)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.app.cash.turbine)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    ksp(libs.hilt.android.compiler)
}
