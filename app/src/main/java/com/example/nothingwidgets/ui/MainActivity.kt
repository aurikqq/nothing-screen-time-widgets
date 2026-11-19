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
            label(box, if (access) "✓ Всё готово к работе" else "Разрешите доступ к статистике", 22f,
                color(if (access) M.attr.colorOnSecondaryContainer else M.attr.colorOnTertiaryContainer))
            label(box, "Данные остаются на телефоне. Доступ нужен для подсчёта времени в приложениях и перерывов.", 14f, color(if (access) M.attr.colorOnSecondaryContainer else M.attr.colorOnTertiaryContainer))
            action(box, if (access) "Управлять доступом" else "Предоставить доступ", primary = !access) {
                try { startActivity(UsageSource(this).permissionIntent()) }
                catch (_: android.content.ActivityNotFoundException) { notice("Системный экран настроек недоступен.") }
            }
        }
        card { box ->
            label(box, "Добавить виджеты", 22f)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            box.addView(row)
            action(row, "Away Time", weight = true) { pinWidget(com.aurikqq.noswidgets.widget.AwayWidgetProvider::class.java) }
            action(row, "Screen Time", weight = true) { pinWidget(com.aurikqq.noswidgets.widget.ScreenTimeWidgetProvider::class.java) }
        }
        card { box ->
            label(box, "Away Time", 25f)
            label(box, "Расписание сна", 14f, color(M.attr.colorOnSurfaceVariant))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            box.addView(row)
            action(row, "Сон\n${format(settings.sleepStartMinute)}", weight = true) { timePicker(settings.sleepStartMinute) { settings.sleepStartMinute = it; changed() } }
            action(row, "Пробуждение\n${format(settings.sleepEndMinute)}", weight = true) { timePicker(settings.sleepEndMinute) { settings.sleepEndMinute = it; changed() } }
            label(box, "Новый день начинается после пробуждения.", 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            label(box, "Screen Time", 25f)
            label(box, "Ваш ежедневный ориентир", 14f, color(M.attr.colorOnSurfaceVariant))
            val limit = if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) settings.limitMinutes else settings.limitForWidget(widgetId)
            action(box, "${limit / 60} ч ${limit % 60} мин   ·   Изменить лимит", primary = true) { changeLimit(limit) }
            label(box, "Пролистните виджет вниз, чтобы увидеть экранное время.", 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            action(box, "Проверить обновления") { UpdateController.checkManual(this) }
            action(box, "Уведомления об обновлениях") { UpdateController.explainAndRequestNotifications(this) }
            action(box, "Диагностика расчёта") { diagnostics() }
            label(box, "Версия ${packageManager.getPackageInfo(packageName, 0).versionName} · Android 12+\nAndroid может откладывать фоновые обновления.", 12f, color(M.attr.colorOnSurfaceVariant))
        }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) action(root, "Готово", primary = true) {
            updateWidgets(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
        }
        renderUpdate(UpdateController.getState())
        scroll.post { scroll.scrollTo(0, scrollPosition) }
    }

    private fun createUpdateBanner() {
        updateCard = card(M.attr.colorPrimaryContainer) { box ->
            updateTitle = label(box, "Обновление", 22f, color(M.attr.colorOnPrimaryContainer))
            updateMessage = label(box, "", 14f, color(M.attr.colorOnPrimaryContainer))
            updateProgress = LinearProgressIndicator(this).apply { max = 100 }
            box.addView(updateProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            updateAction = action(box, "Скачать", primary = true) {
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
        updateTitle.text = if (state.readyToInstall) "Обновление готово" else if (state.tag.isNullOrBlank()) "Обновления" else "Новая версия ${state.tag}"
        updateMessage.text = state.message
        updateProgress.visibility = if (state.downloading || state.checking) View.VISIBLE else View.GONE
        updateProgress.isIndeterminate = state.progress < 0
        if (state.progress >= 0) updateProgress.setProgressCompat(state.progress, true)
        updateAction.visibility = if (state.checking || state.downloading) View.GONE else View.VISIBLE
        updateAction.text = if (state.readyToInstall) "Установить" else if (state.tag.isNullOrBlank()) "Проверить снова" else "Скачать обновление"
        updateNotes.text = if (state.notes.isNullOrBlank()) "" else "Что нового\n${state.notes}"
        updateNotes.visibility = if (state.notes.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun changeLimit(current: Int) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(24), 0, dp(24), 0) }
        fun field(hintText: String, value: Int): EditText = EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString())
            contentDescription = hintText
            fields.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val hours = field("Часы", current / 60)
        val minutes = field("Минуты", current % 60)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Дневной лимит")
            .setMessage("От 1 минуты до 24 часов").setView(fields).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val h = hours.text.toString().toIntOrNull()
                val m = minutes.text.toString().toIntOrNull()
                if (h == null || m == null || h !in 0..24 || m !in 0..59 || h * 60 + m !in 1..1440) {
                    minutes.error = "Укажите от 1 минуты до 24 часов"
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
                notice("Этот рабочий стол не поддерживает добавление из приложения. Добавьте виджет через меню рабочего стола.")
            }
        } catch (_: IllegalStateException) {
            notice("Не удалось открыть добавление виджета. Попробуйте через меню рабочего стола.")
        } catch (_: SecurityException) {
            notice("Не удалось открыть добавление виджета. Попробуйте через меню рабочего стола.")
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
                clipData = android.content.ClipData.newRawUri("Диагностика", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Отправить диагностику"))
        } catch (_: java.io.IOException) {
            notice("Не удалось создать файл диагностики. Проверьте доступное место и попробуйте снова.")
        } catch (_: android.content.ActivityNotFoundException) {
            notice("Не найдено приложение для отправки файла.")
        } catch (_: IllegalArgumentException) {
            notice("Не удалось подготовить файл диагностики.")
        }
    }
    private fun notice(message: String) { MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton("ОК", null).show() }
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
