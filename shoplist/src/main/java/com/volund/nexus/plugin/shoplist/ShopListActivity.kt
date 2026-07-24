package com.volund.nexus.plugin.shoplist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
 * glasses, so all editing (add / remove / clear checked / voice setup) lives
 * here, built only from the NexusUi/BusTheme kit. The list itself is rendered on
 * the HUD by [ShopListPluginService]. Ends with the mandatory uninstall card.
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
        // Reflect items added elsewhere and the current mic-permission state.
        rebuild()
    }

    /** Defer rebuilds triggered from a click so we never setContentView inside the dispatching view (§6). */
    private fun deferRebuild() = handler.post { rebuild() }

    private fun micGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) deferRebuild()
    }

    private fun rebuild() {
        val items = store.load()
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@ShopListActivity, "Add item"), NexusUi.block())
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(addRow(), NexusUi.block())

            addView(BusTheme.gap(this@ShopListActivity, 20))
            addView(
                NexusUi.cardBody(
                    this@ShopListActivity,
                    "Paste a list from another app, one item per line:",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(pasteRow(), NexusUi.block())

            addView(BusTheme.gap(this@ShopListActivity, 24))
            addView(
                NexusUi.sectionRow(this@ShopListActivity, "Items", "${items.size}"),
                NexusUi.block(),
            )
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
            addView(NexusUi.sectionRow(this@ShopListActivity, "Voice"), NexusUi.block())
            addView(BusTheme.gap(this@ShopListActivity, 10))
            addView(voiceSection(), NexusUi.block())

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
            setOnClickListener {
                if (store.add(input.text.toString())) deferRebuild()
            }
        }
        return LinearLayout(this).apply {
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
    }

    private fun pasteRow(): LinearLayout {
        // Reuse the kit field styling, then switch it to a multi-line paste area.
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

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Shopping List") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    /**
     * Voice setup: the glasses mic reaches STT through the Nexus `microphone`
     * capability (approved in Rokid Nexus → Plugin access) plus the Android
     * RECORD_AUDIO runtime permission the SpeechRecognizer requires. Optional
     * recognition language lives here too.
     */
    private fun voiceSection(): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (!supported) {
            column.addView(NexusUi.cardBody(this, "Voice dictation needs Android 13 or newer."), NexusUi.block())
            return column
        }
        column.addView(
            NexusUi.cardBody(
                this,
                "Add items by voice from the glasses: focus \"Add item by voice\" and tap the ring. " +
                    "Approve the microphone for this plugin in Rokid Nexus → Plugin access, and allow it here.",
            ),
            NexusUi.block(),
        )
        column.addView(BusTheme.gap(this, 10))
        if (micGranted()) {
            column.addView(NexusUi.cardBody(this, "Microphone: allowed on this phone."), NexusUi.block())
            column.addView(BusTheme.gap(this, 10))
            column.addView(languageRow(), NexusUi.block())
        } else {
            column.addView(
                NexusUi.pillButton(this, "Allow microphone").apply {
                    setOnClickListener {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                },
                NexusUi.block(),
            )
        }
        return column
    }

    private fun languageRow(): LinearLayout {
        val input = NexusUi.field(this, "Language e.g. pt-BR (blank = device default)").apply {
            setText(store.voiceLanguage().orEmpty())
        }
        val button = NexusUi.pillButton(this, "Save").apply {
            setOnClickListener {
                store.setVoiceLanguage(input.text.toString())
                Toast.makeText(this@ShopListActivity, "Voice language saved", Toast.LENGTH_SHORT).show()
            }
        }
        return LinearLayout(this).apply {
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
    }

    /** Read the real versionName from the installed package so the header never drifts from the manifest. */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: ""

    private companion object {
        const val REQ_MIC = 4201
    }
}
