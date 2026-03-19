package com.freeisp.wa;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.net.URLEncoder;

/**
 * Sends messages by launching WhatsApp with a deep link intent.
 * The Accessibility Service then auto-taps the send button.
 */
public class WhatsAppSender {
    private static final String TAG = "WhatsAppSender";

    /**
     * Check if the target WhatsApp app is installed
     */
    public static boolean isWhatsAppInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Open WhatsApp chat with the given phone number and pre-filled message.
     * The Accessibility Service will detect the WhatsApp window and auto-click send.
     */
    public static boolean sendMessage(Context context, String phone, String message, String packageName) {
        try {
            if (!isWhatsAppInstalled(context, packageName)) {
                Log.e(TAG, "WhatsApp package not installed: " + packageName);
                return false;
            }

            // Use the WhatsApp API URL scheme
            String encodedMessage = URLEncoder.encode(message, "UTF-8");
            String url = "https://api.whatsapp.com/send?phone=" + phone + "&text=" + encodedMessage;

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

            context.startActivity(intent);

            Log.i(TAG, "WhatsApp intent launched for " + phone + " via " + packageName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send WhatsApp message", e);
            return false;
        }
    }
}
