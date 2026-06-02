package com.akhavanskii.aichallenge.core.utils

fun String.normalizedPromptOrNull(): String? =
    trim()
        .replace(Regex("\\s+"), " ")
        .takeIf { it.isNotBlank() }
