plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.akhavanskii.aichallenge.mcp.github.MainKt")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
