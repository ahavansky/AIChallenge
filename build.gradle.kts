// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.hilt) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kover)
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.screenshot) apply false
}

subprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

dependencies {
  kover(project(":core:utils"))
  kover(project(":core:network"))
  kover(project(":feature:home"))
}

kover {
  reports {
    filters {
      excludes {
        classes(
          "com.akhavanskii.aichallenge.core.network.NetworkBindings*",
          "com.akhavanskii.aichallenge.core.network.NetworkModule*",
          "com.akhavanskii.aichallenge.feature.home.HomeRoute*",
          "com.akhavanskii.aichallenge.feature.home.HomeScreen*",
          "com.akhavanskii.aichallenge.feature.home.HomeTags*",
          "com.akhavanskii.aichallenge.feature.home.PromptLabRoute*",
          "com.akhavanskii.aichallenge.feature.home.PromptLabScreen*",
          "com.akhavanskii.aichallenge.feature.home.PromptLabTags*",
          "*_Factory",
          "*_MembersInjector",
          "*Hilt*",
        )
      }
    }
    total {
      verify {
        rule {
          minBound(70)
        }
      }
    }
  }
}
