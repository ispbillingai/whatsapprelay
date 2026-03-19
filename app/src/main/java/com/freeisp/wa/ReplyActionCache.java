package com.freeisp.wa;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches WhatsApp notification reply actions (PendingIntent + RemoteInput)
 * so we can send messages in the background without opening WhatsApp.
 *
 * When WhatsApp posts a notification with a reply action, we extract and cache it.
 * Later, when we need to send a message to that contact, we reuse the cached action.
 *
 * The PendingIntent remains valid as long as WhatsApp's process is alive,
 * even after the notification is dismissed.
 */
public class ReplyActionCache {
    private static final String TAG = "ReplyActionCache";

    private static final ReplyActionCache instance = new ReplyActionCache();

    public static ReplyActionCache getInstance() {
        return instance;
    }

    // Map: phone number (or conversation key) -> CachedAction
    private final ConcurrentHashMap<String, CachedAction> cache = new ConcurrentHashMap<>();


    public static class CachedAction {
        public final PendingIntent pendingIntent;
        public final RemoteInput remoteInput;
        public final String conversationKey; // notification tag or key
        public final long cachedAt;

        public CachedAction(PendingIntent pendingIntent, RemoteInput remoteInput, String conversationKey) {
            this.pendingIntent = pendingIntent;
            this.remoteInput = remoteInput;
            this.conversationKey = conversationKey;
            this.cachedAt = System.currentTimeMillis();
        }

        /**
         * Check if this cached action is still likely valid (within 24 hours)
         */
        public boolean isValid() {
            return (System.currentTimeMillis() - cachedAt) < 24 * 60 * 60 * 1000L;
        }
    }

    /**
     * Store a reply action for a phone number / conversation.
     * Only stores if the key looks like a real phone number (7+ digits).
     */
    public void put(String phoneOrKey, PendingIntent pendingIntent, RemoteInput remoteInput) {
        String normalized = normalizePhone(phoneOrKey);
        if (normalized.length() < 7) {
            Log.w(TAG, "Skipping cache for non-phone key: " + phoneOrKey + " (normalized: " + normalized + ")");
            return;
        }
        CachedAction action = new CachedAction(pendingIntent, remoteInput, phoneOrKey);
        cache.put(normalized, action);
        Log.i(TAG, "Cached reply action for: " + phoneOrKey + " -> " + normalized + " (total cached: " + cache.size() + ")");
    }

    /**
     * Get a cached reply action for a phone number.
     * Tries exact match first, then suffix match on last 10 digits.
     * Returns null if no confident match — never returns a wrong contact's action.
     */
    public CachedAction get(String phone) {
        String normalized = normalizePhone(phone);
        if (normalized.length() < 7) {
            Log.w(TAG, "Phone too short to match: " + phone);
            return null;
        }

        // Exact match
        CachedAction action = cache.get(normalized);
        if (action != null && action.isValid()) {
            Log.i(TAG, "Cache HIT (exact) for: " + phone);
            return action;
        }

        // Suffix match - compare last 10 digits to handle country code differences
        // e.g., "254712345678" should match "0712345678" or "712345678"
        String suffix = normalized.length() > 10 ? normalized.substring(normalized.length() - 10) : normalized;

        for (String key : cache.keySet()) {
            if (key.length() < 7) continue; // skip garbage keys
            String keySuffix = key.length() > 10 ? key.substring(key.length() - 10) : key;

            // Both suffixes must be at least 7 digits and must match
            if (suffix.length() >= 7 && keySuffix.length() >= 7 && suffix.equals(keySuffix)) {
                action = cache.get(key);
                if (action != null && action.isValid()) {
                    Log.i(TAG, "Cache HIT (suffix) for: " + phone + " matched key: " + key);
                    return action;
                }
            }
        }

        Log.i(TAG, "Cache MISS for: " + phone + " (normalized: " + normalized + ", cache size: " + cache.size() + ")");
        return null;
    }

    /**
     * Remove expired entries
     */
    public void cleanup() {
        cache.entrySet().removeIf(entry -> !entry.getValue().isValid());
    }

    /**
     * Get cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Clear all cached actions
     */
    public void clear() {
        cache.clear();
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        // Remove all non-digits
        return phone.replaceAll("[^0-9]", "");
    }
}
