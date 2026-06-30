package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RagIndexJson {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun encode(index: RagIndex): String = json.encodeToString(index)

    fun decode(encoded: String): RagIndex = json.decodeFromString(encoded)
}
