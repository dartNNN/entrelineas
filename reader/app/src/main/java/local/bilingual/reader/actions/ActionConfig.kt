package local.bilingual.reader.actions

import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's setup of one action on a selection: shown or not, pinned to the selection panel or in its overflow
 * menu, and the text template it receives (see [fillTemplate]). The list order is the display order.
 */
data class ActionConfig(val id: String, val enabled: Boolean = true, val pinned: Boolean = false, val template: String = "{text}")

object ActionConfigs {
    /**
     * Built-in actions in their default order: the reader's own first and shown at once (I know this sense, copy),
     * then flashcards, speech and the rest under "Ещё". Anki and speech take fixed fields, not a template: speech
     * reads the chosen layer in its language.
     */
    val DEFAULTS = listOf(
        ActionConfig(ACTION_KNOWN, pinned = true, template = ""),
        ActionConfig(ACTION_COPY, pinned = true),
        ActionConfig(ACTION_ANKI_WORD, template = ""),
        ActionConfig(ACTION_ANKI_SENTENCE, template = ""),
        ActionConfig(ACTION_SPEAK, template = ""),
        ActionConfig(ACTION_TRANSLATE),
        ActionConfig(ACTION_OPEN_IN_APP),
        ActionConfig(ACTION_SHARE),
    )

    /** Version of the defaults; saved configs of an older version are replaced by the defaults once. */
    const val VERSION = 3

    /**
     * Saved configs merged with the actions available now: saved ones keep their order and settings, actions that
     * disappeared are dropped, new ones are appended as in [DEFAULTS].
     */
    fun merge(saved: List<ActionConfig>, available: List<String>): List<ActionConfig> {
        val kept = saved.filter { it.id in available }
        val known = kept.map { it.id }.toSet()
        val defaults = DEFAULTS.associateBy { it.id }
        val order = DEFAULTS.map { it.id }
        val added = available.filter { it !in known }.sortedBy { id -> order.indexOf(id).takeIf { it >= 0 } ?: order.size }
        return kept + added.map { defaults[it] ?: ActionConfig(it) }
    }

    fun toJson(list: List<ActionConfig>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("enabled", it.enabled).put("pinned", it.pinned).put("template", it.template)
    }).toString()

    fun fromJson(json: String?): List<ActionConfig> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ActionConfig(o.getString("id"), o.optBoolean("enabled", true), o.optBoolean("pinned"), o.optString("template", "{text}"))
        }
    }.getOrDefault(emptyList())

    /** Moves the action at [index] by [delta] places (-1 up, +1 down); out-of-range moves leave the list as it is. */
    fun move(list: List<ActionConfig>, index: Int, delta: Int): List<ActionConfig> {
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return list
        return list.toMutableList().apply { add(target, removeAt(index)) }
    }
}

const val ACTION_COPY = "copy"
const val ACTION_SHARE = "share"
const val ACTION_TRANSLATE = "translate"
const val ACTION_KNOWN = "known"
const val ACTION_SPEAK = "speak"
const val ACTION_ANKI_WORD = "anki_word"
const val ACTION_ANKI_SENTENCE = "anki_sentence"
/** Sends the text to an app chosen from the reader's own list (PROCESS_TEXT and SEND apps). */
const val ACTION_OPEN_IN_APP = "open_in_app"
