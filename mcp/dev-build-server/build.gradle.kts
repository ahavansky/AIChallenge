plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.akhavanskii.aichallenge.mcp.dev.buildserver.MainKt")
}

dependencies {
    implementation(project(":mcp:dev-common"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
