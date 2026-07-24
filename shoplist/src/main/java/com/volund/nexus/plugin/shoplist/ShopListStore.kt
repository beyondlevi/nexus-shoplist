package com.volund.nexus.plugin.shoplist

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * File-backed persistence for the shopping list, shared by the phone settings
 * Activity (which edits it) and the headless plugin service (which renders it).
 *
 * Backed by SharedPreferences holding a single JSON array. Limits are enforced
 * here so the HUD surface can never be handed a list that violates the card
 * contract (row count / line length).
 */
class ShopListStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Encrypted store for the OpenAI key + voice config; falls back to plain prefs if crypto init fails. */
    private val secure: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECURE_PREFS,
                masterKey,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { appContext.getSharedPreferences("${SECURE_PREFS}_plain", Context.MODE_PRIVATE) }
    }

    fun load(): List<ShopItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ShopItem(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    label = obj.optString("label"),
                    done = obj.optBoolean("done", false),
                )
            }.filter { it.label.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun save(items: List<ShopItem>) {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("label", item.label)
                    .put("done", item.done),
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    /** Append a new unchecked item. Returns false if the label is blank or the list is full. */
    fun add(label: String): Boolean {
        val clean = label.trim().take(MAX_LABEL_CHARS)
        if (clean.isEmpty()) return false
        val items = load().toMutableList()
        if (items.size >= MAX_ITEMS) return false
        items.add(ShopItem(id = UUID.randomUUID().toString(), label = clean))
        save(items)
        return true
    }

    /**
     * Import pasted multi-line text, one item per non-blank line. Appends until
     * the list is full and returns how many items were actually added.
     */
    fun addLines(raw: String): Int {
        val labels = ShopListParser.parseLines(raw, MAX_LABEL_CHARS)
        if (labels.isEmpty()) return 0
        val items = load().toMutableList()
        var added = 0
        for (label in labels) {
            if (items.size >= MAX_ITEMS) break
            items.add(ShopItem(id = UUID.randomUUID().toString(), label = label))
            added++
        }
        if (added > 0) save(items)
        return added
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** Drop every checked item (the "clear done" action on the phone screen). */
    fun clearChecked() {
        save(load().filterNot { it.done })
    }

    /* ---------------- voice dictation (OpenAI STT) ---------------- */

    fun openAiKey(): String = secure.getString(KEY_OPENAI, "").orEmpty()
    fun setOpenAiKey(key: String) = secure.edit().putString(KEY_OPENAI, key.trim()).apply()

    fun isSttEnabled(): Boolean = secure.getBoolean(KEY_STT_ENABLED, false)
    fun setSttEnabled(enabled: Boolean) = secure.edit().putBoolean(KEY_STT_ENABLED, enabled).apply()

    /** Forced transcription language (ISO code); blank = auto-detect. */
    fun sttLanguage(): String = secure.getString(KEY_STT_LANG, "").orEmpty()
    fun setSttLanguage(code: String) = secure.edit().putString(KEY_STT_LANG, code.trim()).apply()

    fun sttModel(): String = secure.getString(KEY_STT_MODEL, SpeechToText.DEFAULT_MODEL).orEmpty()
    fun setSttModel(model: String) =
        secure.edit().putString(KEY_STT_MODEL, model.trim().ifBlank { SpeechToText.DEFAULT_MODEL }).apply()

    /** Voice is usable only when enabled AND a key is set. */
    fun voiceReady(): Boolean = isSttEnabled() && openAiKey().isNotBlank()

    private companion object {
        const val PREFS = "shoplist"
        const val SECURE_PREFS = "shoplist_secure"
        const val KEY_ITEMS = "items"
        const val KEY_OPENAI = "openai.key"
        const val KEY_STT_ENABLED = "stt.enabled"
        const val KEY_STT_LANG = "stt.language"
        const val KEY_STT_MODEL = "stt.model"
        // Below the 64-row card ceiling; the state machine windows anything larger.
        const val MAX_ITEMS = 60
        // Leaves ample room under the 240-char surface line limit after the cursor/box prefix.
        const val MAX_LABEL_CHARS = 120
    }
}
