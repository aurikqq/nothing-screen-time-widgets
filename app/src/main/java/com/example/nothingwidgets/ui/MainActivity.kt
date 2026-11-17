package com.example.nothingwidgets.ui

import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
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
import com.example.nothingwidgets.background.scheduleRefresh
import com.example.nothingwidgets.background.updateWidgets
import com.example.nothingwidgets.data.SettingsStore
import com.example.nothingwidgets.data.UsageSource
import com.example.nothingwidgets.update.UpdateController
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
        label(root, "ВАШ РИТМ", 12f, color(androidx.appcompat.R.attr.colorPrimary)).apply { letterSpacing = .14f }
        label(root, "Nothing Widgets", 34f).apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 650, false) }
        label(root, "Меньше отвлечений. Больше времени для себя.", 15f, color(M.attr.colorOnSurfaceVariant))
        createUpdateBanner()
        val access = UsageSource(this).hasAccess()
        card(if (access) M.attr.colorSecondaryContainer else M.attr.colorTertiaryContainer) { box ->
            label(box, if (access) "✓ Всё готово к работе" else "Разрешите доступ к статистике", 22f,
                color(if (access) M.attr.colorOnSecondaryContainer else M.attr.colorOnTertiaryContainer))
            label(box, "Данные остаются на телефоне. Доступ нужен для подсчёта времени в приложениях и перерывов.", 14f)
            action(box, if (access) "Управлять доступом" else "Предоставить доступ", primary = !access) {
                try { startActivity(UsageSource(this).permissionIntent()) }
                catch (_: android.content.ActivityNotFoundException) { notice("Системный экран настроек недоступен.") }
            }
        }
        card { box ->
            label(box, "Away Time", 25f)
            label(box, "Расписание сна", 14f, color(M.attr.colorOnSurfaceVariant))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            box.addView(row)
            action(row, "Сон\n${format(settings.sleepStartMinute)}", weight = true) { timePicker(settings.sleepStartMinute) { settings.sleepStartMinute = it; changed() } }
            action(row, "Пробуждение\n${format(settings.sleepEndMinute)}", weight = true) { timePicker(settings.sleepEndMinute) { settings.sleepEndMinute = it; changed() } }
            label(box, "Учитываются перерывы от 10 минут. Новый день начинается после пробуждения.", 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            label(box, "Screen Time", 25f)
            label(box, "Ваш ежедневный ориентир", 14f, color(M.attr.colorOnSurfaceVariant))
            val limit = if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) settings.limitMinutes else settings.limitForWidget(widgetId)
            action(box, "${limit / 60} ч ${limit % 60} мин   ·   Изменить лимит", primary = true) { changeLimit(limit) }
            label(box, "При превышении лимита виджет станет красным. Страницы переключаются вертикальным жестом.", 13f, color(M.attr.colorOnSurfaceVariant))
        }
        card { box ->
            label(box, "Приложение", 22f)
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
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(current.toString()); selectAll(); setPadding(dp(24), dp(12), dp(24), dp(12)) }
        MaterialAlertDialogBuilder(this).setTitle("Лимит в минутах").setMessage("От 1 до 1440 минут в день").setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val minutes = input.text.toString().toIntOrNull()
                if (minutes != null && minutes in 1..1440) {
                    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) settings.limitMinutes = minutes else settings.setWidgetLimit(widgetId, minutes)
                    changed()
                } else notice("Укажите число от 1 до 1440.")
            }.show()
    }
    private fun diagnostics() {
        val summary = com.example.nothingwidgets.data.StatisticsRepository(this).diagnosticSummary()
        MaterialAlertDialogBuilder(this).setTitle("Диагностика расчёта").setMessage(summary).setNeutralButton("Копировать") { _, _ ->
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Диагностика расчёта", summary))
        }.setPositiveButton("Готово", null).show()
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
