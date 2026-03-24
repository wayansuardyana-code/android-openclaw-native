package com.openclaw.android.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openclaw.android.data.entity.MemoryEntity
import kotlin.math.sqrt

object VectorSearch {
    private val gson = Gson()

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    fun parseEmbedding(json: String): FloatArray? {
        if (json.isBlank()) return null
        return try {
            val type = object : TypeToken<List<Float>>() {}.type
            gson.fromJson<List<Float>>(json, type).toFloatArray()
        } catch (e: Exception) { null }
    }

    fun search(query: FloatArray, memories: List<MemoryEntity>, topK: Int = 5): List<Pair<MemoryEntity, Float>> {
        return memories
            .mapNotNull { mem ->
                val emb = parseEmbedding(mem.embedding) ?: return@mapNotNull null
                mem to cosineSimilarity(query, emb)
            }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
