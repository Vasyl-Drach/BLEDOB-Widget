package com.example.bledobwidget;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 42;
    private TextView deviceLabel, message;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private ArrayAdapter<String> listAdapter;
    private final ArrayList<String> addresses = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spinner protocol;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(12));
        root.setBackgroundColor(0xfff7f9fa);
        TextView title = text("BLEDOB · управление лентой", 21);
        title.setTextColor(0xff172a33); root.addView(title);
        TextView help = text("1. Выбери Bluetooth-устройство.  2. Нажми «Добавить кнопку 1×1» для создания компактного ярлыка на рабочем столе.", 14);
        help.setPadding(0, dp(12), 0, dp(14)); root.addView(help);
        Button pinShortcut = new Button(this);
        pinShortcut.setText("Добавить кнопку 1×1 на рабочий стол");
        root.addView(pinShortcut);
        pinShortcut.setOnClickListener(v -> pinPowerShortcut());
        deviceLabel = text("", 16); root.addView(deviceLabel);
        Button scan = new Button(this); scan.setText("Найти контроллер Bluetooth"); root.addView(scan);
        message = text("", 13); root.addView(message);
        ListView list = new ListView(this);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(listAdapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView protoLabel = text("Протокол управления", 14); root.addView(protoLabel);
        protocol = new Spinner(this);
        ArrayAdapter<String> opts = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Lotus Lantern (основной)", "BLEDOB / LotusLamp X (альтернативный)"});
        protocol.setAdapter(opts);
        protocol.setSelection(LedWidgetProvider.prefs(this).getInt("protocol", 0));
        root.addView(protocol);
        Button testOn = new Button(this); testOn.setText("Проверить: ВКЛ"); root.addView(testOn);
        Button testOff = new Button(this); testOff.setText("Проверить: ВЫКЛ"); root.addView(testOff);
        TextView note = text("Если команда не работает, попробуй второй протокол. Перед проверкой закрой Lotus Lantern.", 13);
        root.addView(note);
        setContentView(root);
        refreshDeviceLabel();
        scan.setOnClickListener(v -> { if (ensurePermissions()) startScan(); });
        list.setOnItemClickListener((parent, view, position, id) -> {
            LedWidgetProvider.prefs(this).edit().putString("address", addresses.get(position)).putBoolean("on", false).apply();
            stopScan(); refreshDeviceLabel(); LedWidgetProvider.render(this, null);
            message.setText("Устройство сохранено. Теперь проверь команды ВКЛ и ВЫКЛ.");
        });
        protocol.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                LedWidgetProvider.prefs(MainActivity.this).edit().putInt("protocol", position).apply();
            }
        });
        testOn.setOnClickListener(v -> sendExplicit(true));
        testOff.setOnClickListener(v -> sendExplicit(false));
        ensurePermissions();
    }

    // A pinned launcher shortcut occupies exactly one icon cell, even when the launcher
    // refuses to make an AppWidget smaller than 2x2. It triggers the same BLE service.
    private void pinPowerShortcut() {
        ShortcutManager shortcuts = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Этот лаунчер не разрешает добавлять ярлыки из приложений", Toast.LENGTH_LONG).show();
            return;
        }
        Intent action = new Intent(this, ToggleActivity.class);
        action.setAction("com.example.bledobwidget.SHORTCUT_TOGGLE");
        action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "bledob_power_1x1")
                .setShortLabel("Свет")
                .setLongLabel("BLEDOB: включить/выключить")
                .setIcon(Icon.createWithResource(this, R.drawable.widget_power))
                .setIntent(action)
                .build();
        try {
            if (shortcuts.requestPinShortcut(info, null)) {
                Toast.makeText(this, "Подтверди добавление кнопки на рабочий стол", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Лаунчер отказал в добавлении ярлыка", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка создания ярлыка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private TextView text(String s, int size) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(0xff34444d); return t;
    }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private void refreshDeviceLabel() {
        String addr = LedWidgetProvider.prefs(this).getString("address", "");
        deviceLabel.setText(addr.isEmpty() ? "Контроллер ещё не выбран" : "Выбран контроллер: " + addr);
    }
    private boolean ensurePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST); return false; }
        return true;
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(requestCode, perms, grants);
        if (requestCode == PERMISSION_REQUEST) {
            for (int result : grants) if (result != PackageManager.PERMISSION_GRANTED) {
                message.setText("Нужно разрешить Bluetooth (на Android 11 и ниже — местоположение для поиска BLE)."); return;
            }
            message.setText("Разрешения получены. Нажми «Найти контроллер Bluetooth».");
        }
    }
    private void startScan() {
        stopScan();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { message.setText("Включи Bluetooth на телефоне."); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { message.setText("Сканер BLE недоступен."); return; }
        seen.clear(); addresses.clear(); listAdapter.clear();
        message.setText("Ищу устройства 12 секунд. Контроллер должен быть включён.");
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                try {
                    BluetoothDevice device = result.getDevice();
                    String mac = device.getAddress();
                    if (seen.add(mac)) {
                        String name = device.getName();
                        if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
                        addresses.add(mac);
                        listAdapter.add((name == null ? "Без имени" : name) + "  •  " + mac);
                    }
                } catch (SecurityException ignored) {}
            }
            @Override public void onScanFailed(int errorCode) { message.setText("Ошибка сканирования: " + errorCode); }
        };
        try { scanner.startScan(scanCallback); }
        catch (Exception e) { message.setText("Ошибка BLE: " + e.getMessage()); return; }
        handler.postDelayed(() -> { stopScan(); message.setText("Поиск завершён. Выбери BLEDOB или ELK-BLEDOB из списка."); }, 12000);
    }
    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            scanCallback = null; scanner = null;
        }
    }
    private void sendExplicit(boolean on) {
        if (!ensurePermissions()) return;
        if (LedWidgetProvider.prefs(this).getString("address", "").isEmpty()) {
            message.setText("Сначала выбери контроллер из списка."); return;
        }
        Intent svc = new Intent(this, BleCommandService.class);
        svc.setAction(BleCommandService.ACTION_SET);
        svc.putExtra(BleCommandService.EXTRA_ON, on);
        try { startForegroundService(svc); message.setText("Отправляю " + (on ? "ВКЛ" : "ВЫКЛ") + "… смотри результат на виджете."); }
        catch (Exception e) { message.setText("Не удалось запустить: " + e.getMessage()); }
    }
    @Override protected void onDestroy() { stopScan(); super.onDestroy(); }
}
