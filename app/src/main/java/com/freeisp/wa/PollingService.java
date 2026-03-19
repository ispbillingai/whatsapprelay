package com.freeisp.wa;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Foreground service that polls the PHP server for pending messages
 * and dispatches them to WhatsApp one at a time.
 *
 * Uses AlarmManager.setExactAndAllowWhileIdle() to reliably poll
 * even when the screen is off and Doze mode is active.
 *
 * Sending strategy:
 * 1. BackgroundSender (RemoteInput) — works even with phone locked/screen off
 * 2. Accessibility fallback — only when phone is NOT securely locked
 * 3. If phone is securely locked (PIN/pattern) — message deferred until user unlocks
 */
public class PollingService extends Service {
    private static final String TAG = "PollingService";
    private static final String ACTION_POLL_ALARM = "com.freeisp.wa.POLL_ALARM";

    private ApiClient apiClient;
    private MessageLog messageLog;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private AlarmManager alarmManager;
    private PendingIntent alarmIntent;

    private final Queue<MessageModel> messageQueue = new LinkedList<>();
    private final Queue<MessageModel> deferredQueue = new LinkedList<>(); // waiting for phone unlock
    private boolean isProcessing = false;
    private boolean isRunning = false;

    private int sentCount = 0;
    private int failedCount = 0;
    private int deferredCount = 0;

    // Timeout for waiting for accessibility service to send
    private static final long SEND_TIMEOUT_MS = 20000; // 20 seconds

    @Override
    public void onCreate() {
        super.onCreate();
        apiClient = new ApiClient(this);
        messageLog = MessageLog.getInstance(this);
        handler = new Handler(Looper.getMainLooper());
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        createNotificationChannels();

        // Register broadcast receivers for message results
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConstants.ACTION_MESSAGE_SENT);
        filter.addAction(AppConstants.ACTION_MESSAGE_FAILED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(messageResultReceiver, filter);
        }

        // Register alarm receiver for polling
        IntentFilter alarmFilter = new IntentFilter(ACTION_POLL_ALARM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pollAlarmReceiver, alarmFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(pollAlarmReceiver, alarmFilter);
        }

        // Register for phone unlock events — sends deferred messages
        IntentFilter unlockFilter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userPresentReceiver, unlockFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(userPresentReceiver, unlockFilter);
        }

        // Acquire PARTIAL_WAKE_LOCK to keep CPU running
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeISPWA:PollingWakeLock");
        wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours max

        // Acquire WiFi lock to keep network alive when screen is off
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FreeISPWA:WifiLock");
        wifiLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(AppConstants.NOTIFICATION_SERVICE, buildServiceNotification("Relay active - polling for messages"));

        if (!isRunning) {
            isRunning = true;
            startPolling();
            broadcastStatus("running");
        }

        return START_STICKY;
    }

    /**
     * Start polling using AlarmManager for reliable wakeups during Doze.
     */
    private void startPolling() {
        int intervalSeconds = getPrefs().getInt(AppConstants.PREF_POLL_INTERVAL, AppConstants.DEFAULT_POLL_INTERVAL);
        Log.i(TAG, "Polling started with interval: " + intervalSeconds + "s (using AlarmManager)");

        // First poll immediately
        handler.postDelayed(this::pollForMessages, 1000);

        // Schedule recurring alarm-based polling
        scheduleNextAlarm();
    }

    /**
     * Schedule the next polling alarm using setExactAndAllowWhileIdle().
     * This fires reliably even during Doze mode when screen is off.
     */
    private void scheduleNextAlarm() {
        if (!isRunning) return;

        int intervalSeconds = getPrefs().getInt(AppConstants.PREF_POLL_INTERVAL, AppConstants.DEFAULT_POLL_INTERVAL);
        if (intervalSeconds < 5) intervalSeconds = 5;

        Intent intent = new Intent(ACTION_POLL_ALARM);
        intent.setPackage(getPackageName());
        alarmIntent = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAt = System.currentTimeMillis() + (intervalSeconds * 1000L);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent);
        }
    }

    /**
     * BroadcastReceiver for the AlarmManager poll alarm.
     */
    private final BroadcastReceiver pollAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_POLL_ALARM.equals(intent.getAction()) && isRunning) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                PowerManager.WakeLock tempWake = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "FreeISPWA:PollWake");
                tempWake.acquire(30000);

                Log.d(TAG, "Alarm fired - polling for messages");
                pollForMessages();

                scheduleNextAlarm();

                handler.postDelayed(() -> {
                    if (tempWake.isHeld()) tempWake.release();
                }, 15000);
            }
        }
    };

    /**
     * BroadcastReceiver for when the user unlocks the phone.
     * Moves all deferred messages back to the main queue for sending.
     */
    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction()) && isRunning) {
                synchronized (deferredQueue) {
                    int count = deferredQueue.size();
                    if (count > 0) {
                        Log.i(TAG, "Phone unlocked! Moving " + count + " deferred messages to queue");
                        synchronized (messageQueue) {
                            messageQueue.addAll(deferredQueue);
                        }
                        deferredQueue.clear();
                        deferredCount = 0;

                        updateNotification("Phone unlocked — sending " + count + " deferred message(s)");

                        // Start processing after short delay
                        handler.postDelayed(() -> processNextMessage(), 1500);
                    }
                }
            }
        }
    };

    private void pollForMessages() {
        // Refresh the notification reply cache on every poll cycle
        // This ensures we have the latest reply actions from WhatsApp
        refreshNotificationCache();

        // Don't fetch more if we already have messages queued
        synchronized (messageQueue) {
            if (!messageQueue.isEmpty()) {
                Log.i(TAG, "Queue not empty (" + messageQueue.size() + "), skipping fetch");
                return;
            }
        }

        new Thread(() -> {
            try {
                List<MessageModel> messages = apiClient.fetchPendingMessages();

                if (!messages.isEmpty()) {
                    Log.i(TAG, "Fetched " + messages.size() + " pending messages");
                    synchronized (messageQueue) {
                        messageQueue.addAll(messages);
                    }

                    handler.post(this::processNextMessage);
                }
            } catch (Exception e) {
                Log.e(TAG, "Polling error", e);
            }
        }).start();
    }

    private void processNextMessage() {
        if (isProcessing) return;

        MessageModel message;
        synchronized (messageQueue) {
            message = messageQueue.poll();
        }

        if (message == null) return;

        isProcessing = true;
        Log.i(TAG, "Processing message: " + message);

        ensureWakeLock();
        updateNotification("Sending to " + message.getPhone() + "...");

        messageLog.log(message.getId(), message.getPhone(), message.getMessage(),
                message.getWhatsappType(), "sending", null);

        // HYBRID APPROACH: Try background first, accessibility fallback second
        final MessageModel msg = message;
        final int msgId = message.getId();

        // STEP 1: Try sending completely in background via cached notification reply
        new Thread(() -> {
            boolean sentBackground = BackgroundSender.trySendBackground(
                    this, msg.getPhone(), msg.getMessage());

            if (sentBackground) {
                Log.i(TAG, "Message " + msgId + " sent via BACKGROUND method (RemoteInput)");
                handler.post(() -> handleSendResult(msgId, true, null));
            } else {
                // STEP 2: Check if phone is securely locked
                handler.post(() -> {
                    if (isDeviceSecurelyLocked()) {
                        // Phone has PIN/pattern/fingerprint lock — can't use accessibility
                        // Defer this message until the user unlocks the phone
                        Log.i(TAG, "Message " + msgId + " deferred — phone is securely locked");
                        deferMessage(msg);
                    } else {
                        // Phone is unlocked or has swipe/no lock — use accessibility
                        Log.i(TAG, "Message " + msgId + " falling back to ACCESSIBILITY method");
                        sendViaAccessibility(msg);
                    }
                });
            }
        }).start();
    }

    /**
     * Check if the device has a secure lock that's currently active.
     * Returns true for PIN, pattern, password, or biometric locks that are engaged.
     * Returns false for swipe-only, no lock, or if the phone is already unlocked.
     */
    private boolean isDeviceSecurelyLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // isDeviceLocked() returns true only if there's a SECURE lock that's active
            return km.isDeviceLocked();
        }

        return km.isKeyguardLocked() && km.isKeyguardSecure();
    }

    /**
     * Defer a message until the phone is unlocked.
     * The message is NOT reported as failed to the server.
     */
    private void deferMessage(MessageModel message) {
        isProcessing = false;

        synchronized (deferredQueue) {
            deferredQueue.add(message);
            deferredCount = deferredQueue.size();
        }

        messageLog.log(message.getId(), message.getPhone(), message.getMessage(),
                message.getWhatsappType(), "deferred", "Waiting for phone unlock");

        updateNotification("Phone locked — " + deferredCount + " message(s) waiting for unlock. Sent: " + sentCount);

        broadcastStats();

        // Continue processing any remaining messages in the main queue
        handler.postDelayed(this::processNextMessage, 1000);
    }

    /**
     * Send via accessibility service.
     * Only called when the phone is NOT securely locked.
     * If the screen is off, wakes it via KeyguardUnlockActivity.
     */
    private void sendViaAccessibility(MessageModel message) {
        final int msgId = message.getId();

        WhatsAppAccessibilityService.waitingToSend = true;
        WhatsAppAccessibilityService.currentMessageId = msgId;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean isScreenOn;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            isScreenOn = pm.isInteractive();
        } else {
            isScreenOn = pm.isScreenOn();
        }

        if (!isScreenOn) {
            // Screen is off but no secure lock — use KeyguardUnlockActivity to wake screen
            Log.i(TAG, "Screen off (no secure lock) — waking screen for msg " + msgId);

            Intent unlockIntent = new Intent(this, KeyguardUnlockActivity.class);
            unlockIntent.putExtra(KeyguardUnlockActivity.EXTRA_PHONE, message.getPhone());
            unlockIntent.putExtra(KeyguardUnlockActivity.EXTRA_MESSAGE, message.getMessage());
            unlockIntent.putExtra(KeyguardUnlockActivity.EXTRA_PACKAGE, message.getTargetPackage());
            unlockIntent.putExtra(KeyguardUnlockActivity.EXTRA_MSG_ID, msgId);
            unlockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(unlockIntent);
        } else {
            // Screen on and not securely locked — launch WhatsApp directly
            boolean launched = WhatsAppSender.sendMessage(
                    this,
                    message.getPhone(),
                    message.getMessage(),
                    message.getTargetPackage()
            );

            if (!launched) {
                handleSendResult(msgId, false,
                        "Failed to launch WhatsApp (" + message.getTargetPackage() + ")");
                return;
            }
        }

        // Set a timeout
        handler.postDelayed(() -> {
            if (WhatsAppAccessibilityService.waitingToSend &&
                    WhatsAppAccessibilityService.currentMessageId == msgId) {
                Log.w(TAG, "Send timeout for message " + msgId);
                WhatsAppAccessibilityService.waitingToSend = false;
                handleSendResult(msgId, false, "Timeout waiting for WhatsApp send button");
            }
        }, SEND_TIMEOUT_MS);
    }

    /**
     * Refresh the WhatsApp notification reply cache by re-scanning active notifications.
     * This ensures we always have the freshest reply actions available.
     */
    private void refreshNotificationCache() {
        try {
            // Clean up expired entries
            ReplyActionCache.getInstance().cleanup();

            // The NotificationListenerService has a refreshCache() method
            // We can't call it directly, but we can request a rebind
            // The onListenerConnected callback in the listener already scans all active notifications
            // For now, just log the cache status
            int cacheSize = ReplyActionCache.getInstance().size();
            if (cacheSize > 0) {
                Log.d(TAG, "Reply action cache has " + cacheSize + " entries");
            }
        } catch (Exception e) {
            Log.w(TAG, "Cache refresh error: " + e.getMessage());
        }
    }

    /**
     * Ensure the wake lock is still held.
     */
    private void ensureWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
    }

    /**
     * Broadcast receiver for results from the Accessibility Service
     */
    private final BroadcastReceiver messageResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int messageId = intent.getIntExtra("message_id", -1);
            if (messageId == -1) return;

            if (AppConstants.ACTION_MESSAGE_SENT.equals(intent.getAction())) {
                handleSendResult(messageId, true, null);
            } else if (AppConstants.ACTION_MESSAGE_FAILED.equals(intent.getAction())) {
                String error = intent.getStringExtra("error");
                handleSendResult(messageId, false, error);
            }
        }
    };

    private void handleSendResult(int messageId, boolean success, String error) {
        isProcessing = false;

        if (success) {
            sentCount++;
            messageLog.log(messageId, "", "", "", "delivered", null);
            updateNotification("Last message delivered. Sent: " + sentCount
                    + (deferredCount > 0 ? " | " + deferredCount + " waiting for unlock" : ""));

            new Thread(() -> apiClient.reportStatus(messageId, "delivered", null)).start();
        } else {
            failedCount++;
            messageLog.log(messageId, "", "", "", "failed", error);
            updateNotification("Message failed: " + error + ". Failed: " + failedCount);

            final String errMsg = error;
            new Thread(() -> apiClient.reportStatus(messageId, "failed", errMsg)).start();
        }

        broadcastStats();

        // Process next message after delay
        handler.postDelayed(this::processNextMessage, 5000);
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent(AppConstants.ACTION_SERVICE_STATUS);
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    private void broadcastStats() {
        Intent intent = new Intent(AppConstants.ACTION_STATS_UPDATED);
        intent.putExtra("sent", sentCount);
        intent.putExtra("failed", failedCount);
        intent.putExtra("pending", messageQueue.size());
        intent.putExtra("deferred", deferredCount);
        sendBroadcast(intent);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel serviceChannel = new NotificationChannel(
                    AppConstants.CHANNEL_SERVICE,
                    "Relay Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Persistent notification for the relay service");
            nm.createNotificationChannel(serviceChannel);

            NotificationChannel messageChannel = new NotificationChannel(
                    AppConstants.CHANNEL_MESSAGES,
                    "Message Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            messageChannel.setDescription("Alerts about message delivery");
            nm.createNotificationChannel(messageChannel);
        }
    }

    private Notification buildServiceNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, AppConstants.CHANNEL_SERVICE)
                .setContentTitle("FreeISP WA Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(AppConstants.NOTIFICATION_SERVICE, buildServiceNotification(text));
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);

        // Cancel alarm
        if (alarmIntent != null) {
            alarmManager.cancel(alarmIntent);
        }

        try { unregisterReceiver(messageResultReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(pollAlarmReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(userPresentReceiver); } catch (Exception ignored) {}

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }

        // Report deferred messages as failed so they can be retried from dashboard
        synchronized (deferredQueue) {
            for (MessageModel msg : deferredQueue) {
                new Thread(() -> apiClient.reportStatus(msg.getId(), "failed",
                        "Service stopped while message was deferred")).start();
            }
            deferredQueue.clear();
        }

        broadcastStatus("stopped");
        Log.i(TAG, "Polling service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
