package com.freeisp.wa;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private EditText etServerUrl, etApiKey, etPollInterval;
    private Spinner spinnerWhatsApp;
    private TextView tvStatus, tvStats, tvAccessibilityStatus, tvNotifListenerStatus;
    private MaterialButton btnStartService, btnStopService;

    private SharedPreferences prefs;
    private MessageLog messageLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        messageLog = MessageLog.getInstance(this);

        initViews();
        loadSettings();
        updateAccessibilityStatus();
        updateNotifListenerStatus();
        updateStats();

        // Check for app updates silently on launch
        new UpdateChecker(this).checkForUpdateSilent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateAccessibilityStatus();
        updateNotifListenerStatus();
        updateStats();
        registerReceivers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}
    }

    private void initViews() {
        etServerUrl = findViewById(R.id.etServerUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etPollInterval = findViewById(R.id.etPollInterval);
        spinnerWhatsApp = findViewById(R.id.spinnerWhatsApp);
        tvStatus = findViewById(R.id.tvStatus);
        tvStats = findViewById(R.id.tvStats);
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnStopService = findViewById(R.id.btnStopService);

        // Save settings
        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());

        // Test connection
        findViewById(R.id.btnTestConnection).setOnClickListener(v -> testConnection());

        // Start service
        btnStartService.setOnClickListener(v -> startRelayService());

        // Stop service
        btnStopService.setOnClickListener(v -> stopRelayService());

        // Enable accessibility
        findViewById(R.id.btnEnableAccessibility).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Find 'FreeISP WA' and enable it", Toast.LENGTH_LONG).show();
        });

        // Notification listener (for background sending)
        tvNotifListenerStatus = findViewById(R.id.tvNotifListenerStatus);
        findViewById(R.id.btnEnableNotifListener).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Find 'FreeISP WA' and enable it for background sending", Toast.LENGTH_LONG).show();
        });

        // Overlay permission (display over other apps)
        findViewById(R.id.btnBatteryOptimization).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Enable 'Display over other apps' for FreeISP WA", Toast.LENGTH_LONG).show();
            } else {
                disableBatteryOptimization();
            }
        });

        // View logs
        findViewById(R.id.btnViewLogs).setOnClickListener(v -> {
            startActivity(new Intent(this, LogActivity.class));
        });

        // Version display and update button
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tvVersion)).setText("FreeISP WA v" + versionName);
        } catch (Exception ignored) {}

        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show();
            new UpdateChecker(this).checkForUpdate();
        });
    }

    private void loadSettings() {
        etServerUrl.setText(prefs.getString(AppConstants.PREF_SERVER_URL, ""));
        etApiKey.setText(prefs.getString(AppConstants.PREF_API_KEY, ""));
        etPollInterval.setText(String.valueOf(prefs.getInt(AppConstants.PREF_POLL_INTERVAL, AppConstants.DEFAULT_POLL_INTERVAL)));

        String waType = prefs.getString(AppConstants.PREF_WHATSAPP_TYPE, "whatsapp");
        spinnerWhatsApp.setSelection("whatsapp_business".equals(waType) ? 1 : 0);
    }

    private void saveSettings() {
        String serverUrl = etServerUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        String pollStr = etPollInterval.getText().toString().trim();
        int pollInterval = pollStr.isEmpty() ? AppConstants.DEFAULT_POLL_INTERVAL : Integer.parseInt(pollStr);
        String waType = spinnerWhatsApp.getSelectedItemPosition() == 1 ? "whatsapp_business" : "whatsapp";

        if (serverUrl.isEmpty()) {
            etServerUrl.setError("Required");
            return;
        }
        if (!serverUrl.startsWith("https://") && !serverUrl.startsWith("http://")) {
            etServerUrl.setError("URL must start with https://");
            Toast.makeText(this, "Server URL must start with https://", Toast.LENGTH_LONG).show();
            return;
        }
        if (apiKey.isEmpty()) {
            etApiKey.setError("Required");
            return;
        }
        if (pollInterval < 2) {
            pollInterval = 2; // Minimum 2 seconds
        }

        prefs.edit()
                .putString(AppConstants.PREF_SERVER_URL, serverUrl)
                .putString(AppConstants.PREF_API_KEY, apiKey)
                .putInt(AppConstants.PREF_POLL_INTERVAL, pollInterval)
                .putString(AppConstants.PREF_WHATSAPP_TYPE, waType)
                .apply();

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show();
    }

    private void testConnection() {
        Toast.makeText(this, "Testing connection...", Toast.LENGTH_SHORT).show();
        saveSettings();

        new Thread(() -> {
            ApiClient client = new ApiClient(this);
            ApiClient.ApiResult result = client.testConnection();

            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle(result.success ? "Connection OK" : "Connection Failed")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }

    private void startRelayService() {
        // Validate settings
        if (etServerUrl.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Please configure server URL first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isAccessibilityServiceEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Accessibility Required")
                    .setMessage("The accessibility service must be enabled for auto-sending to work. Enable it now?")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("'Display over other apps' permission is needed to send WhatsApp messages from the background. Enable it now?")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        saveSettings();

        // Start foreground service
        Intent serviceIntent = new Intent(this, PollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        prefs.edit().putBoolean(AppConstants.PREF_SERVICE_RUNNING, true).apply();
        updateServiceStatus();
        Toast.makeText(this, "Relay service started!", Toast.LENGTH_SHORT).show();
    }

    private void stopRelayService() {
        Intent serviceIntent = new Intent(this, PollingService.class);
        stopService(serviceIntent);

        prefs.edit().putBoolean(AppConstants.PREF_SERVICE_RUNNING, false).apply();
        updateServiceStatus();
        Toast.makeText(this, "Relay service stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateServiceStatus() {
        boolean running = prefs.getBoolean(AppConstants.PREF_SERVICE_RUNNING, false);

        if (running) {
            tvStatus.setText(R.string.status_running);
            tvStatus.setTextColor(0xFF25D366); // Green
            btnStartService.setVisibility(View.GONE);
            btnStopService.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText(R.string.status_idle);
            tvStatus.setTextColor(0xFFF44336); // Red
            btnStartService.setVisibility(View.VISIBLE);
            btnStopService.setVisibility(View.GONE);
        }
    }

    private void updateAccessibilityStatus() {
        boolean accessEnabled = isAccessibilityServiceEnabled();
        boolean overlayEnabled = Settings.canDrawOverlays(this);

        String status = "Accessibility: " + (accessEnabled ? "ON" : "OFF")
                + " | Overlay: " + (overlayEnabled ? "ON" : "OFF");
        tvAccessibilityStatus.setText(status);
        tvAccessibilityStatus.setTextColor(
                (accessEnabled && overlayEnabled) ? 0xFF25D366 : 0xFFF44336);
    }

    private void updateNotifListenerStatus() {
        boolean enabled = isNotificationListenerEnabled();
        int cacheSize = ReplyActionCache.getInstance().size();
        String status = "Notification Listener: " + (enabled ? "ON" : "OFF")
                + " | Cached replies: " + cacheSize;
        tvNotifListenerStatus.setText(status);
        tvNotifListenerStatus.setTextColor(enabled ? 0xFF25D366 : 0xFFF44336);
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            String myComponent = new ComponentName(this,
                    WhatsAppNotificationListener.class).flattenToString();
            return flat.contains(myComponent);
        }
        return false;
    }

    private void updateStats() {
        int sent = messageLog.getCountByStatus("delivered");
        int failed = messageLog.getCountByStatus("failed");
        int sending = messageLog.getCountByStatus("sending");
        tvStats.setText("Sent: " + sent + " | Failed: " + failed + " | Processing: " + sending);
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC);

        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void disableBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConstants.ACTION_SERVICE_STATUS);
        filter.addAction(AppConstants.ACTION_STATS_UPDATED);
        filter.addAction(AppConstants.ACTION_CACHE_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AppConstants.ACTION_STATS_UPDATED.equals(intent.getAction())) {
                updateStats();
            } else if (AppConstants.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                updateServiceStatus();
            } else if (AppConstants.ACTION_CACHE_UPDATED.equals(intent.getAction())) {
                updateNotifListenerStatus();
            }
        }
    };
}
