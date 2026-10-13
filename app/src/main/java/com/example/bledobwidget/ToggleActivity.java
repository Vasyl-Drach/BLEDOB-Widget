package com.example.bledobwidget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** One-cell shortcut entry point. No persistent UI; delegates to existing BLE service. */
public class ToggleActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (LedWidgetProvider.prefs(this).getString("address", "").isEmpty()
                || !LedWidgetProvider.hasConnectPermission(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        try {
            Intent svc = new Intent(this, BleCommandService.class);
            svc.setAction(BleCommandService.ACTION_TOGGLE);
            startForegroundService(svc);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось отправить команду: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
