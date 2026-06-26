plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.akhavanskii.aichallenge.mcp.pipeline.save.MainKt")
}

dependencies {
    implementation(project(":mcp:pipeline-common"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
