package com.freeisp.wa;

import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

/**
 * Transparent activity that wakes the screen and launches WhatsApp.
 *
 * Only used when the phone has NO secure lock (swipe or none).
 * For securely-locked phones (PIN/pattern/fingerprint), messages
 * are deferred until the user naturally unlocks the phone.
 */
public class KeyguardUnlockActivity extends androidx.appcompat.app.AppCompatActivity {
    private static final String TAG = "KeyguardUnlock";

    public static final String EXTRA_PHONE = "phone";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_MSG_ID = "msg_id";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen and wake the screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        String phone = getIntent().getStringExtra(EXTRA_PHONE);
        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        String targetPackage = getIntent().getStringExtra(EXTRA_PACKAGE);
        int messageId = getIntent().getIntExtra(EXTRA_MSG_ID, -1);

        if (phone == null || message == null || targetPackage == null) {
            Log.e(TAG, "Missing intent extras");
            finish();
            return;
        }

        Log.i(TAG, "Waking screen and launching WhatsApp for message " + messageId);

        // Small delay for screen to fully wake, then launch WhatsApp
        handler.postDelayed(() -> {
            WhatsAppSender.sendMessage(this, phone, message, targetPackage);
            // Finish after WhatsApp has launched
            handler.postDelayed(this::finish, 2000);
        }, 500);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
