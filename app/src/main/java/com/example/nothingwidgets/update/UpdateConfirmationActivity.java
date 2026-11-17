package com.example.nothingwidgets.update;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Internal notification destination; download remains an explicit tap. */
public final class UpdateConfirmationActivity extends Activity {
    static final String EXTRA_CONFIRMATION = "system_install_confirmation";
    private TextView status;
    private Button action;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent confirmation = getIntent().getParcelableExtra(EXTRA_CONFIRMATION);
        if (confirmation != null) { startActivity(confirmation); finish(); return; }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        status = new TextView(this); layout.addView(status);
        action = new Button(this); layout.addView(action);
        action.setOnClickListener(view -> {
            UpdateController.UiState state = UpdateController.getState();
            if (state.readyToInstall) UpdateController.install(this);
            else if (state.visible && !state.tag.isEmpty()) UpdateController.download(this);
            else UpdateController.checkManual(this);
        });
        setContentView(layout);
    }
    @Override protected void onResume() {
        super.onResume();
        if (status == null) return;
        UpdateController.attach(this, state -> {
            status.setText(state.message + (state.tag.isEmpty() ? "" : "\n" + state.tag + "\n" + state.notes));
            action.setText(state.downloading ? (state.progress < 0 ? state.message : "Загрузка " + state.progress + "%") : state.readyToInstall ? "Установить" : state.visible && !state.tag.isEmpty() ? "Скачать" : "Проверить обновления");
            action.setEnabled(!state.downloading && !state.checking);
        });
    }
    @Override protected void onPause() { UpdateController.detach(this); super.onPause(); }
}
