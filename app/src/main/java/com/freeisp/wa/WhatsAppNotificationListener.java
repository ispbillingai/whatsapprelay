package com.freeisp.wa;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Listens for WhatsApp notifications and caches their reply actions.
 *
 * When WhatsApp receives a message, it posts a notification with an inline
 * reply action (RemoteInput). We capture that action so we can later send
 * messages through it completely in the background — even when the phone
 * is locked.
 *
 * Phone number extraction order:
 * 1. notification.getShortcutId() → "254712345678@s.whatsapp.net" (most reliable)
 * 2. sbn.getTag() → may contain phone number
 * 3. extras "android.conversationTitle" → phone for unsaved contacts
 * 4. notification title → phone for unsaved contacts
 */
public class WhatsAppNotificationListener extends NotificationListenerService {
    private static final String TAG = "WANotifListener";

    private static volatile boolean isConnected = false;

    public static boolean isServiceConnected() {
        return isConnected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        isConnected = true;
        Log.i(TAG, "Notification Listener connected");
        refreshCache();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isConnected = false;
        Log.w(TAG, "Notification Listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Keep cached actions — PendingIntent often remains valid after notification dismissed
    }

    /**
     * Re-scan all active WhatsApp notifications and refresh the cache.
     * Called on listener connect and can be called periodically.
     */
    public void refreshCache() {
        try {
            StatusBarNotification[] activeNotifs = getActiveNotifications();
            if (activeNotifs != null) {
                int cached = 0;
                for (StatusBarNotification sbn : activeNotifs) {
                    if (processNotification(sbn)) cached++;
                }
                Log.i(TAG, "Cache refresh: scanned " + activeNotifs.length
                        + " notifications, cached " + cached
                        + " reply actions (total: " + ReplyActionCache.getInstance().size() + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing cache", e);
        }
    }

    /**
     * Process a notification and cache its reply action if it's from WhatsApp.
     * Returns true if a reply action was cached.
     */
    private boolean processNotification(StatusBarNotification sbn) {
        if (sbn == null) return false;

        String packageName = sbn.getPackageName();

        // Only process WhatsApp notifications
        if (!AppConstants.WHATSAPP_PACKAGE.equals(packageName) &&
                !AppConstants.WHATSAPP_BUSINESS_PACKAGE.equals(packageName)) {
            return false;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.actions == null) return false;

        // Skip group notifications — we can only send to individual chats
        String tag = sbn.getTag();
        if (tag != null && tag.contains("@g.us")) {
            return false;
        }

        // Find the reply action
        for (Notification.Action action : notification.actions) {
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0) {
                PendingIntent pendingIntent = action.actionIntent;
                RemoteInput remoteInput = remoteInputs[0];

                // Extract phone number using multiple methods
                String phoneKey = extractPhone(sbn, notification);

                if (phoneKey != null && !phoneKey.isEmpty()) {
                    ReplyActionCache.getInstance().put(phoneKey, pendingIntent, remoteInput);

                    // Broadcast cache update
                    Intent cacheIntent = new Intent(AppConstants.ACTION_CACHE_UPDATED);
                    cacheIntent.putExtra("cache_size", ReplyActionCache.getInstance().size());
                    sendBroadcast(cacheIntent);
                    return true;
                } else {
                    Bundle extras = notification.extras;
                    String title = extras != null ? extras.getString(Notification.EXTRA_TITLE, "") : "";
                    Log.d(TAG, "Could not extract phone: title=" + title + " tag=" + tag);
                }

                break; // Only one reply action per notification
            }
        }

        return false;
    }

    /**
     * Extract phone number from a WhatsApp notification using multiple strategies.
     */
    private String extractPhone(StatusBarNotification sbn, Notification notification) {
        String tag = sbn.getTag();
        Bundle extras = notification.extras;
        String title = extras != null ? extras.getString(Notification.EXTRA_TITLE, "") : "";

        // ===== METHOD 1: shortcutId (most reliable, Android 8+) =====
        // Format: "254712345678@s.whatsapp.net"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String shortcutId = notification.getShortcutId();
            if (shortcutId != null && shortcutId.contains("@s.whatsapp.net")) {
                String phone = shortcutId.split("@")[0].replaceAll("[^0-9]", "");
                if (phone.length() >= 7) {
                    Log.d(TAG, "Phone from shortcutId: " + phone);
                    return phone;
                }
            }
        }

        // ===== METHOD 2: Notification tag =====
        // WhatsApp tags for individual chats: "254712345678@s.whatsapp.net" or just contain digits
        if (tag != null) {
            if (tag.contains("@s.whatsapp.net")) {
                String phone = tag.split("@")[0].replaceAll("[^0-9]", "");
                if (phone.length() >= 7) {
                    Log.d(TAG, "Phone from tag (@s.whatsapp.net): " + phone);
                    return phone;
                }
            }
            // Try extracting any long digit sequence from tag
            String tagDigits = tag.replaceAll("[^0-9]", "");
            if (tagDigits.length() >= 7) {
                Log.d(TAG, "Phone from tag (digits): " + tagDigits);
                return tagDigits;
            }
        }

        // ===== METHOD 3: Notification key =====
        // sbn.getKey() format sometimes includes phone: "0|com.whatsapp|...|254712345678@s.whatsapp.net|..."
        String key = sbn.getKey();
        if (key != null && key.contains("@s.whatsapp.net")) {
            try {
                int atIndex = key.indexOf("@s.whatsapp.net");
                // Walk backwards to find the start of the phone number
                int start = atIndex - 1;
                while (start >= 0 && Character.isDigit(key.charAt(start))) start--;
                start++;
                String phone = key.substring(start, atIndex);
                if (phone.length() >= 7) {
                    Log.d(TAG, "Phone from key: " + phone);
                    return phone;
                }
            } catch (Exception e) {
                // ignore parsing errors
            }
        }

        // ===== METHOD 4: conversationTitle extra =====
        if (extras != null) {
            CharSequence convTitle = extras.getCharSequence("android.conversationTitle");
            if (convTitle != null) {
                String cleaned = convTitle.toString().replaceAll("[^0-9]", "");
                if (cleaned.length() >= 7) {
                    Log.d(TAG, "Phone from conversationTitle: " + cleaned);
                    return cleaned;
                }
            }
        }

        // ===== METHOD 5: Notification title (works for unsaved contacts) =====
        if (title != null) {
            String cleaned = title.replaceAll("[^0-9+]", "");
            // Only use if it looks like a phone number (not a name)
            String digits = cleaned.replaceAll("[^0-9]", "");
            if (digits.length() >= 7) {
                Log.d(TAG, "Phone from title: " + digits);
                return digits;
            }
        }

        // ===== METHOD 6: extras EXTRA_PEOPLE or EXTRA_PEOPLE_LIST =====
        if (extras != null) {
            // Try EXTRA_PEOPLE_LIST (Android P+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    java.util.ArrayList<android.app.Person> people =
                            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
                    if (people != null) {
                        for (android.app.Person person : people) {
                            String uri = person.getUri();
                            if (uri != null && uri.startsWith("tel:")) {
                                String phone = uri.substring(4).replaceAll("[^0-9]", "");
                                if (phone.length() >= 7) {
                                    Log.d(TAG, "Phone from PEOPLE_LIST: " + phone);
                                    return phone;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // Try EXTRA_PEOPLE (older API)
            String[] people = extras.getStringArray(Notification.EXTRA_PEOPLE);
            if (people != null) {
                for (String person : people) {
                    if (person != null && person.startsWith("tel:")) {
                        String phone = person.substring(4).replaceAll("[^0-9]", "");
                        if (phone.length() >= 7) {
                            Log.d(TAG, "Phone from EXTRA_PEOPLE: " + phone);
                            return phone;
                        }
                    }
                }
            }
        }

        return null;
    }
}
