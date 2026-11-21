package com.aurikqq.noswidgets.ui

import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aurikqq.noswidgets.background.scheduleRefresh
import com.aurikqq.noswidgets.background.updateWidgets
import com.aurikqq.noswidgets.data.SettingsStore
import com.aurikqq.noswidgets.data.UsageSource
import com.aurikqq.noswidgets.update.UpdateController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.R as M

/** One scrollable settings screen; widget rendering has its own independent theme. */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var root: LinearLayout
    private lateinit var updateCard: MaterialCardView
    private lateinit var updateTitle: TextView
    private lateinit var updateMessage: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateProgress: LinearProgressIndicator
    private lateinit var updateAction: MaterialButton
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val updateListener = UpdateController.Listener { state -> renderUpdate(state) }

    override fun onCreate(state: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        settings = SettingsStore(this)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            setResult(RESULT_CANCELED)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) == null) { finish(); return }
        }
        scheduleRefresh(this)
        UpdateController.scheduleConfiguredChecks(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            // Re-read Usage Access after returning from Settings.
            buildUi()
            updateWidgets(this)
            UpdateController.attach(this, updateListener)
        }
    }
    override fun onPause() { UpdateController.detach(this); super.onPause() }

    private fun buildUi() {
        val scrollPosition = if (::root.isInitialized) (root.parent as? ScrollView)?.scrollY ?: 0 else 0
        root = column(0).apply { setPadding(dp(20), dp(16), dp(20), dp(20)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(M.attr.colorSurface))
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        label(root, "NOS Widgets", 34f).apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 650, false) }
        createUpdateBanner()
        val access = UsageSource(this).hasAccess()
        card(if (access) M.attr.colorSecondaryContainer else M.attr.colorTertiaryContainer) { box ->
            label(box, if (access) getString(com.aurikqq.noswidgets.R.string.loc_0) else getString(com.aurikqq.noswidgets.R.string.loc_1), 22f,
                color(if (access) M.attr.colorOnSecondaryContainer else M.attr.colorOnTertiaryContainer))
            label(box, getString(com.aurikqq.noswidgets.R.string.loc_2), 14f, color(if (access) M.attr.colorOnSecondaryContainer else M.attr.colorOnTertiaryContainer))
            action(box, if (access) getString(com.aurikqq.noswidgets.R.string.loc_3) else getString(com.aurikqq.noswidgets.R.string.loc_4), primary = !access) {
                try { startActivity(UsageSource(this).permissionIntent()) }
                catch (_: android.content.ActivityNotFoundException) { notice(getString(com.aurikqq.noswidgets.R.string.loc_5)) }
            }
        }
        card { box ->
            label(box, getString(com.aurikqq.noswidgets.R.string.loc_6), 22f)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            box.addView(row)
            action(row, "Away Time", weight = true) { pinWidget(com.aurikqq.noswidgets.widget.AwayWidgetProvider::class.java) }
            action(row, "Screen Time", weight = true) { pinWidget(com.aurikqq.noswidgets.widget.ScreenTimeWidgetProvider::class.java) }
            label(box, getString(com.aurikqq.noswidgets.R.string.loc_7), 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            label(box, "Away Time", 22f)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            box.addView(row)
            action(row, "${getString(com.aurikqq.noswidgets.R.string.sleep)}\n${format(settings.sleepStartMinute)}", weight = true) { timePicker(settings.sleepStartMinute) { settings.sleepStartMinute = it; changed() } }
            action(row, "${getString(com.aurikqq.noswidgets.R.string.wake)}\n${format(settings.sleepEndMinute)}", weight = true) { timePicker(settings.sleepEndMinute) { settings.sleepEndMinute = it; changed() } }
            label(box, getString(com.aurikqq.noswidgets.R.string.loc_8), 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            label(box, "Screen Time", 22f)
            val limit = if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) settings.limitMinutes else settings.limitForWidget(widgetId)
            action(box, getString(com.aurikqq.noswidgets.R.string.limit_button, limit / 60, limit % 60), primary = true) { changeLimit(limit) }
            label(box, getString(com.aurikqq.noswidgets.R.string.loc_9), 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            listItem(box, com.aurikqq.noswidgets.R.drawable.ic_update, getString(com.aurikqq.noswidgets.R.string.loc_10), getString(com.aurikqq.noswidgets.R.string.loc_11)) { UpdateController.checkManual(this) }
            listItem(box, com.aurikqq.noswidgets.R.drawable.ic_notifications, getString(com.aurikqq.noswidgets.R.string.loc_12), getString(com.aurikqq.noswidgets.R.string.loc_13), if (UpdateController.notificationsAllowed(this)) getString(com.aurikqq.noswidgets.R.string.loc_14) else getString(com.aurikqq.noswidgets.R.string.loc_15)) { UpdateController.explainAndRequestNotifications(this) }
            listItem(box, com.aurikqq.noswidgets.R.drawable.ic_diagnostics, getString(com.aurikqq.noswidgets.R.string.loc_16), getString(com.aurikqq.noswidgets.R.string.loc_17)) { diagnostics() }
            label(box, getString(com.aurikqq.noswidgets.R.string.app_version, packageManager.getPackageInfo(packageName, 0).versionName), 12f, color(M.attr.colorOnSurfaceVariant))
        }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) action(root, getString(com.aurikqq.noswidgets.R.string.loc_18), primary = true) {
            updateWidgets(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
        }
        renderUpdate(UpdateController.getState())
        scroll.post { scroll.scrollTo(0, scrollPosition) }
    }

    private fun createUpdateBanner() {
        updateCard = card(M.attr.colorPrimaryContainer) { box ->
            updateTitle = label(box, getString(com.aurikqq.noswidgets.R.string.loc_19), 22f, color(M.attr.colorOnPrimaryContainer))
            updateMessage = label(box, "", 14f, color(M.attr.colorOnPrimaryContainer))
            updateProgress = LinearProgressIndicator(this).apply { max = 100 }
            box.addView(updateProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            updateAction = action(box, getString(com.aurikqq.noswidgets.R.string.loc_20), primary = true) {
                val current = UpdateController.getState()
                if (current.readyToInstall) UpdateController.install(this)
                else if (current.tag.isNullOrBlank()) UpdateController.checkManual(this)
                else UpdateController.download(this)
            }
            updateNotes = label(box, "", 14f, color(M.attr.colorOnPrimaryContainer)).apply { setTextIsSelectable(true) }
        }
        updateCard.setOnClickListener {
            val state = UpdateController.getState()
            if (!state.downloading && !state.checking && !state.readyToInstall && !state.tag.isNullOrBlank()) UpdateController.download(this)
        }
        updateCard.visibility = View.GONE
    }

    private fun renderUpdate(state: UpdateController.UiState) {
        if (!::updateCard.isInitialized) return
        updateCard.visibility = if (state.visible) View.VISIBLE else View.GONE
        updateTitle.text = if (state.readyToInstall) getString(com.aurikqq.noswidgets.R.string.loc_21) else if (state.tag.isNullOrBlank()) getString(com.aurikqq.noswidgets.R.string.loc_22) else getString(com.aurikqq.noswidgets.R.string.new_version, state.tag)
        updateMessage.text = state.message
        updateProgress.visibility = if (state.downloading || state.checking) View.VISIBLE else View.GONE
        updateProgress.isIndeterminate = state.progress < 0
        if (state.progress >= 0) updateProgress.setProgressCompat(state.progress, true)
        updateAction.visibility = if (state.checking || state.downloading) View.GONE else View.VISIBLE
        updateAction.text = if (state.readyToInstall) getString(com.aurikqq.noswidgets.R.string.loc_23) else if (state.tag.isNullOrBlank()) getString(com.aurikqq.noswidgets.R.string.loc_24) else getString(com.aurikqq.noswidgets.R.string.loc_25)
        updateNotes.text = if (state.notes.isNullOrBlank()) "" else "${getString(com.aurikqq.noswidgets.R.string.whats_new)}\n${state.notes}"
        updateNotes.visibility = if (state.notes.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun changeLimit(current: Int) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        fun field(hintText: String, value: Int): EditText = EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString())
            contentDescription = hintText
            fields.addView(this, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            fields.addView(TextView(this@MainActivity).apply {
                text = if (hintText == getString(com.aurikqq.noswidgets.R.string.loc_26)) getString(com.aurikqq.noswidgets.R.string.loc_28) else getString(com.aurikqq.noswidgets.R.string.loc_29)
                textSize = 16f
                setPadding(dp(6), 0, dp(18), 0)
            })
        }
        val hours = field(getString(com.aurikqq.noswidgets.R.string.loc_26), current / 60)
        val minutes = field(getString(com.aurikqq.noswidgets.R.string.loc_27), current % 60)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(getString(com.aurikqq.noswidgets.R.string.loc_30))
            .setMessage(getString(com.aurikqq.noswidgets.R.string.loc_31)).setView(fields).setNegativeButton(getString(com.aurikqq.noswidgets.R.string.loc_32), null)
            .setPositiveButton(getString(com.aurikqq.noswidgets.R.string.loc_33), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val h = hours.text.toString().toIntOrNull()
                val m = minutes.text.toString().toIntOrNull()
                if (h == null || m == null || h !in 0..24 || m !in 0..59 || h * 60 + m !in 1..1440) {
                    minutes.error = getString(com.aurikqq.noswidgets.R.string.loc_34)
                } else {
                    val total = h * 60 + m
                    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) settings.limitMinutes = total else settings.setWidgetLimit(widgetId, total)
                    dialog.dismiss(); changed()
                }
            }
        }
        dialog.show()
    }

    private fun pinWidget(provider: Class<*>) {
        val manager = AppWidgetManager.getInstance(this)
        try {
            if (!manager.isRequestPinAppWidgetSupported || !manager.requestPinAppWidget(ComponentName(this, provider), null, null)) {
                notice(getString(com.aurikqq.noswidgets.R.string.loc_35))
            }
        } catch (_: IllegalStateException) {
            notice(getString(com.aurikqq.noswidgets.R.string.loc_36))
        } catch (_: SecurityException) {
            notice(getString(com.aurikqq.noswidgets.R.string.loc_36))
        }
    }
    private fun diagnostics() {
        try {
            val summary = com.aurikqq.noswidgets.data.StatisticsRepository(this).diagnosticSummary()
            val directory = java.io.File(cacheDir, "diagnostics")
            if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Cannot create diagnostics directory")
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = java.io.File.createTempFile("nos-diagnostics-", ".txt", directory)
            file.writeText(summary, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.diagnostics", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(getString(com.aurikqq.noswidgets.R.string.loc_37), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(com.aurikqq.noswidgets.R.string.loc_38)))
        } catch (_: java.io.IOException) {
            notice(getString(com.aurikqq.noswidgets.R.string.loc_39))
        } catch (_: android.content.ActivityNotFoundException) {
            notice(getString(com.aurikqq.noswidgets.R.string.loc_40))
        } catch (_: IllegalArgumentException) {
            notice(getString(com.aurikqq.noswidgets.R.string.loc_41))
        }
    }
    private fun notice(message: String) { MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton(getString(com.aurikqq.noswidgets.R.string.loc_42), null).show() }
    private fun changed() { updateWidgets(this); buildUi() }
    private fun timePicker(current: Int, save: (Int) -> Unit) { TimePickerDialog(this, { _, h, m -> save(h * 60 + m) }, current / 60, current % 60, true).show() }
    private fun format(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(attribute: Int) = MaterialColors.getColor(this, attribute, "MainActivity")
    private fun column(padding: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding),dp(padding),dp(padding),dp(padding)) }
    private fun label(parent: LinearLayout, value: String, size: Float, ink: Int = color(M.attr.colorOnSurface)): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(0, dp(4), 0, dp(6)); parent.addView(this)
    }
    private fun card(surface: Int = M.attr.colorSurfaceContainer, content: (LinearLayout) -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply { radius = dp(28).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(color(surface)) }
        val box = column(20)
        card.addView(box)
        root.addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(16) })
        content(box)
        return card
    }
    private fun listItem(parent: LinearLayout, icon: Int, title: String, description: String, status: String? = null, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12)); isClickable = true; isFocusable = true
            val out = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) setBackgroundResource(out.resourceId)
            setOnClickListener { click() }
        }
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(icon); setColorFilter(color(M.attr.colorOnSurfaceVariant)); contentDescription = null
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        val texts = column(0)
        texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(color(M.attr.colorOnSurface)) })
        texts.addView(TextView(this).apply { text = description; textSize = 13f; setTextColor(color(M.attr.colorOnSurfaceVariant)); setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (status != null) row.addView(TextView(this).apply { text = status; textSize = 13f; setTextColor(color(androidx.appcompat.R.attr.colorPrimary)) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    private fun action(parent: LinearLayout, text: String, primary: Boolean = false, weight: Boolean = false, click: () -> Unit): MaterialButton {
        val style = if (primary) M.attr.materialButtonStyle else M.attr.materialButtonTonalStyle
        return MaterialButton(this, null, style).apply {
            this.text = text; isAllCaps = false; textSize = 15f; minHeight = dp(56); cornerRadius = dp(22)
            setPadding(dp(16),dp(10),dp(16),dp(10)); setOnClickListener { click() }
            parent.addView(this, LinearLayout.LayoutParams(if (weight) 0 else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (weight) 1f else 0f).apply {
                topMargin = dp(4); bottomMargin = dp(4); if (weight) { marginStart = dp(3); marginEnd = dp(3) }
            })
        }
    }
}
