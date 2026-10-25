package com.kita.clips

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Clip(
    val id: String,
    val text: String,
    val ts: Long,
    val device: String,
)

/** Tiny JSON-file persistence for the local clip history. */
object ClipStore {
    @Synchronized
    fun load(file: File): List<Clip> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Clip(
                    id = o.getString("id"),
                    text = o.getString("text"),
                    ts = o.getLong("ts"),
                    device = o.optString("device", "?"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(file: File, clips: List<Clip>) {
        val arr = JSONArray()
        clips.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("ts", it.ts)
                    .put("device", it.device),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
