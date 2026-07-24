package com.volund.nexus.plugin.shoplist

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * Phone-side manager for the shopping list. Headless plugins never draw on the
 * glasses, so all editing (add / remove / clear checked) and voice setup (OpenAI
 * key, language, model) live here, built only from the NexusUi/BusTheme kit. The
 * list is rendered on the HUD by [ShopListPluginService]. Ends with the
 * mandatory uninstall card.
 */
class ShopListActivity : Activity() {
    private val store by lazy { ShopListStore(this) }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild() // reflect items/config changed elsewhere
    }

    /** Defer rebuilds triggered from a click so we never setContentView inside the dispatching view (§6). */
    private fun deferRebuild() = handler.post { rebuild() }

    private fun rebuild() {
        val items = store.load()
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@ShopListActivity, "Add item"), NexusUi.block())
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(addRow(), NexusUi.block())

            addView(BusTheme.gap(this@ShopListActivity, 20))
            addView(
                NexusUi.cardBody(this@ShopListActivity, "Paste a list from another app, one item per line:"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(pasteRow(), NexusUi.block())

            addView(BusTheme.gap(this@ShopListActivity, 24))
            addView(NexusUi.sectionRow(this@ShopListActivity, "Items", "${items.size}"), NexusUi.block())
            addView(BusTheme.gap(this@ShopListActivity, 10))
            if (items.isEmpty()) {
                addView(
                    NexusUi.cardBody(
                        this@ShopListActivity,
                        "Nothing yet. Add an item above, then open Shopping List from the glasses launcher.",
                    ),
                    NexusUi.block(),
                )
            } else {
                items.forEach { item ->
                    addView(itemRow(item), NexusUi.block())
                    addView(BusTheme.gap(this@ShopListActivity, 8))
                }
                if (items.any { it.done }) {
                    addView(BusTheme.gap(this@ShopListActivity, 4))
                    addView(clearCheckedButton(), NexusUi.block())
                }
            }

            addView(BusTheme.gap(this@ShopListActivity, 24))
            addView(NexusUi.sectionRow(this@ShopListActivity, "Voice dictation"), NexusUi.block())
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(sttSection(), NexusUi.block())

            addView(BusTheme.gap(this@ShopListActivity, 24))
            addView(NexusUi.sectionRow(this@ShopListActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@ShopListActivity,
                    R.drawable.nexus_glyph_cart,
                    "Shopping List",
                    "Headless Nexus plugin . v${versionName()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@ShopListActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun addRow(): LinearLayout {
        val input = NexusUi.field(this, "e.g. Milk")
        val button = NexusUi.pillButton(this, "Add").apply {
            setOnClickListener { if (store.add(input.text.toString())) deferRebuild() }
        }
        return row(input, button)
    }

    private fun pasteRow(): LinearLayout {
        val input = NexusUi.field(this, "Milk\nEggs\nBread").apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            val pad = NexusUi.dp(this@ShopListActivity, 12)
            setPadding(NexusUi.dp(this@ShopListActivity, 16), pad, NexusUi.dp(this@ShopListActivity, 16), pad)
        }
        val button = NexusUi.pillButton(this, "Add all").apply {
            setOnClickListener {
                val added = store.addLines(input.text.toString())
                if (added > 0) {
                    Toast.makeText(
                        this@ShopListActivity,
                        if (added == 1) "Added 1 item" else "Added $added items",
                        Toast.LENGTH_SHORT,
                    ).show()
                    deferRebuild()
                } else {
                    Toast.makeText(this@ShopListActivity, "Nothing to add", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END },
            )
        }
    }

    private fun itemRow(item: ShopItem): LinearLayout {
        val box = if (item.done) "[x]" else "[ ]"
        return NexusUi.navCard(
            this,
            "$box ${item.label}",
            if (item.done) "checked . tap to remove" else "tap to remove",
        ) {
            store.remove(item.id)
            deferRebuild()
        }
    }

    private fun clearCheckedButton() = NexusUi.outlinePillButton(this, "Clear checked").apply {
        setOnClickListener {
            store.clearChecked()
            deferRebuild()
        }
    }

    /**
     * Voice dictation config. The glasses mic reaches the plugin over the hub
     * (Nexus `microphone` capability, approved in Plugin access — no Android
     * permission). Transcription is OpenAI's buffered `/v1/audio/transcriptions`,
     * so it needs an API key, and a model/language.
     */
    private fun sttSection(): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            NexusUi.cardBody(
                this,
                "Say items from the glasses: focus \"Add item by voice\" and tap the ring. " +
                    "Approve the microphone for this plugin in Rokid Nexus → Plugin access. " +
                    "Transcription uses OpenAI, so set your API key below.",
            ),
            NexusUi.block(),
        )
        column.addView(BusTheme.gap(this, 10))

        val enabled = store.isSttEnabled()
        column.addView(
            NexusUi.pillButton(this, if (enabled) "Voice: ON" else "Voice: OFF").apply {
                setOnClickListener {
                    store.setSttEnabled(!enabled)
                    deferRebuild()
                }
            },
            NexusUi.block(),
        )
        column.addView(BusTheme.gap(this, 10))

        val keyField = NexusUi.field(this, "OpenAI API key (sk-…)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(store.openAiKey())
        }
        column.addView(keyField, NexusUi.block())
        column.addView(BusTheme.gap(this, 8))

        val langField = NexusUi.field(this, "Language e.g. pt, en (blank = auto)").apply {
            setText(store.sttLanguage())
        }
        column.addView(langField, NexusUi.block())
        column.addView(BusTheme.gap(this, 8))

        val modelField = NexusUi.field(this, "Model (${SpeechToText.MODELS.values.joinToString(" / ")})").apply {
            setText(store.sttModel())
        }
        column.addView(modelField, NexusUi.block())
        column.addView(BusTheme.gap(this, 10))

        column.addView(
            NexusUi.pillButton(this, "Save voice settings").apply {
                setOnClickListener {
                    store.setOpenAiKey(keyField.text.toString())
                    store.setSttLanguage(langField.text.toString())
                    store.setSttModel(modelField.text.toString())
                    Toast.makeText(this@ShopListActivity, "Voice settings saved", Toast.LENGTH_SHORT).show()
                    deferRebuild()
                }
            },
            NexusUi.block(),
        )
        return column
    }

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Shopping List") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private fun row(input: EditText, button: android.widget.Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = NexusUi.dp(this@ShopListActivity, 10) },
            )
        }

    /** Read the real versionName from the installed package so the header never drifts from the manifest. */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: ""
}
