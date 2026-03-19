package com.freeisp.wa;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Sends WhatsApp messages completely in the background using cached
 * notification reply actions (RemoteInput + PendingIntent).
 *
 * This works by reusing WhatsApp's own notification reply mechanism:
 * 1. WhatsApp posts a notification with an inline reply action
 * 2. We cache that action (PendingIntent + RemoteInput)
 * 3. When we need to send, we fill the RemoteInput with our text
 * 4. We fire the PendingIntent — WhatsApp sends the message
 *
 * No UI is shown. The message is sent 100% in the background.
 * This only works for contacts who have previously messaged us
 * (i.e., we have a cached reply action for them).
 */
public class BackgroundSender {
    private static final String TAG = "BackgroundSender";

    /**
     * Attempt to send a message completely in background using cached reply action.
     *
     * @param context   Application context
     * @param phone     Target phone number
     * @param message   Message text to send
     * @return true if message was sent via background method, false if no cached action available
     */
    public static boolean trySendBackground(Context context, String phone, String message) {
        ReplyActionCache cache = ReplyActionCache.getInstance();

        // Try to find a cached reply action for this phone number
        ReplyActionCache.CachedAction action = cache.get(phone);

        if (action == null) {
            Log.i(TAG, "No cached reply action for " + phone + ", background send not possible");
            return false;
        }

        return sendViaRemoteInput(context, action, message);
    }

    /**
     * Send a message using a cached RemoteInput action
     */
    private static boolean sendViaRemoteInput(Context context, ReplyActionCache.CachedAction action, String message) {
        try {
            // Create the reply intent with the message text
            Intent replyIntent = new Intent();
            Bundle bundle = new Bundle();
            bundle.putCharSequence(action.remoteInput.getResultKey(), message);
            RemoteInput.addResultsToIntent(new RemoteInput[]{action.remoteInput}, replyIntent, bundle);

            // Fire the PendingIntent — this tells WhatsApp to send the message
            action.pendingIntent.send(context, 0, replyIntent);

            Log.i(TAG, "Message sent via background RemoteInput to: " + action.conversationKey);
            return true;

        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "PendingIntent was cancelled (WhatsApp may have restarted). Removing from cache.");
            // The cached action is no longer valid
            ReplyActionCache.getInstance().clear();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Background send failed: " + e.getMessage(), e);
            return false;
        }
    }
}
