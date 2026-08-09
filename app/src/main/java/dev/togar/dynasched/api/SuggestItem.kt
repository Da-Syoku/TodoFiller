package dev.togar.dynasched.api

import org.json.JSONObject

/** GET /suggest が返すタスク候補1件分（ウィジェット用） */
data class SuggestItem(
    val kind: String,      // "hobby" | "goal"
    val id: Long,
    val title: String,
    val minutes: Int,
    val priority: Int
) {
    companion object {
        fun from(o: JSONObject): SuggestItem = SuggestItem(
            kind = o.optString("kind", "hobby"),
            id = o.optLong("id"),
            title = o.optString("title", "(無題)"),
            minutes = o.optInt("minutes", 30),
            priority = o.optInt("priority", 5)
        )
    }
}
