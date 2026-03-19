package com.freeisp.wa;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Accessibility Service that monitors WhatsApp and auto-clicks the send button.
 *
 * Flow:
 * 1. WhatsApp opens via deep link → may show "Continue to chat" screen
 * 2. This service clicks "Continue to chat" if present
 * 3. Then finds the send button in the chat screen and clicks it
 * 4. Presses back to return to the relay app
 */
public class WhatsAppAccessibilityService extends AccessibilityService {
    private static final String TAG = "WAAccessibility";

    // Static flags to coordinate with PollingService
    public static volatile boolean waitingToSend = false;
    public static volatile int currentMessageId = -1;
    private static volatile boolean sendAttempted = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retryCount = 0;
    private static final int MAX_RETRY = 25;
    private static final long RETRY_DELAY_MS = 300;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility Service connected");

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!waitingToSend) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // Only process WhatsApp events
        if (!packageName.equals(AppConstants.WHATSAPP_PACKAGE) &&
                !packageName.equals(AppConstants.WHATSAPP_BUSINESS_PACKAGE)) {
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            if (!sendAttempted) {
                sendAttempted = true;
                retryCount = 0;
                // Give WhatsApp time to load
                handler.postDelayed(this::attemptInteraction, 500);
            }
        }
    }

    private void attemptInteraction() {
        if (!waitingToSend) return;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            retryIfNeeded();
            return;
        }

        // Step 1: Try to click "Continue to chat" or similar confirmation buttons first
        if (clickContinueButton(rootNode)) {
            Log.i(TAG, "Clicked continue/confirm button, waiting for chat to load...");
            rootNode.recycle();
            // Wait for chat screen to load, then retry to find send button
            handler.postDelayed(this::attemptInteraction, 2000);
            return;
        }

        // Step 2: Try to find and click the send button
        boolean sent = findAndClickSendButton(rootNode);

        if (sent) {
            Log.i(TAG, "Send button clicked for message " + currentMessageId);
            rootNode.recycle();
            onMessageSent();
        } else {
            // Log the UI tree for debugging
            if (retryCount == 5 || retryCount == 15) {
                Log.w(TAG, "Send button not found on attempt " + retryCount + ", dumping UI tree:");
                dumpNodeTree(rootNode, 0);
            }
            rootNode.recycle();
            retryIfNeeded();
        }
    }

    /**
     * Click "Continue to chat", "OK", "CONTINUE", or similar confirmation buttons
     * that WhatsApp shows before opening the chat from a deep link.
     */
    private boolean clickContinueButton(AccessibilityNodeInfo root) {
        // Search by text for common confirmation buttons
        String[] buttonTexts = {
                "Continue to chat", "CONTINUE TO CHAT",
                "Continue", "CONTINUE",
                "OK", "Ok",
                "SEND", // Some versions show a direct send button on the confirmation
        };

        for (String text : buttonTexts) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence nodeText = node.getText();
                    if (nodeText != null && node.isClickable()) {
                        String t = nodeText.toString().toLowerCase();
                        if (t.contains("continue") || t.equals("ok") || t.equals("send")) {
                            Log.i(TAG, "Clicking confirmation button: " + nodeText);
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                    }
                    // Also try clicking the parent if the node itself isn't clickable
                    if (nodeText != null) {
                        String t = nodeText.toString().toLowerCase();
                        if (t.contains("continue") || t.equals("ok")) {
                            AccessibilityNodeInfo parent = node.getParent();
                            if (parent != null && parent.isClickable()) {
                                Log.i(TAG, "Clicking parent of: " + nodeText);
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                parent.recycle();
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean findAndClickSendButton(AccessibilityNodeInfo root) {
        // Strategy 1: Known WhatsApp send button resource IDs
        String[] sendButtonIds = {
                "com.whatsapp:id/send",
                "com.whatsapp.w4b:id/send",
                "com.whatsapp:id/send_btn",
                "com.whatsapp.w4b:id/send_btn",
                "com.whatsapp:id/btn_send",
                "com.whatsapp.w4b:id/btn_send",
        };

        for (String buttonId : sendButtonIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(buttonId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isClickable() && node.isEnabled()) {
                        Log.i(TAG, "Found send button by ID: " + buttonId);
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
            }
        }

        // Strategy 2: Find by content description containing "Send"
        if (findSendByDescription(root)) {
            return true;
        }

        // Strategy 3: Find by text "Send" on clickable views
        if (findSendByText(root)) {
            return true;
        }

        return false;
    }

    private boolean findSendByDescription(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String descStr = desc.toString().toLowerCase();
            // Match "Send" but not "Send to" or "Send message to"
            if (descStr.equals("send") && node.isClickable()) {
                Log.i(TAG, "Found send button by description: " + desc);
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (findSendByDescription(child)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private boolean findSendByText(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Send");
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                // Look for the ImageButton/ImageView send icon (usually has "Send" as content description)
                String className = node.getClassName() != null ? node.getClassName().toString() : "";
                if ((className.contains("ImageButton") || className.contains("ImageView"))
                        && node.isClickable() && node.isEnabled()) {
                    Log.i(TAG, "Found send button by text search: " + className);
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Dump the accessibility node tree for debugging
     */
    private void dumpNodeTree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return;

        String indent = new String(new char[depth]).replace('\0', ' ');
        String id = node.getViewIdResourceName();
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String className = node.getClassName() != null ? node.getClassName().toString() : "";

        Log.d(TAG, indent + className
                + (id != null ? " id=" + id : "")
                + (text != null ? " text=\"" + text + "\"" : "")
                + (desc != null ? " desc=\"" + desc + "\"" : "")
                + (node.isClickable() ? " [CLICKABLE]" : ""));

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNodeTree(child, depth + 1);
                child.recycle();
            }
        }
    }

    private void retryIfNeeded() {
        retryCount++;
        if (retryCount < MAX_RETRY && waitingToSend) {
            handler.postDelayed(this::attemptInteraction, RETRY_DELAY_MS);
        } else if (waitingToSend) {
            Log.e(TAG, "Failed to find send button after " + MAX_RETRY + " attempts");
            onMessageFailed("Could not find send button in WhatsApp");
        }
    }

    private void onMessageSent() {
        int msgId = currentMessageId;
        waitingToSend = false;
        sendAttempted = false;
        retryCount = 0;

        Intent intent = new Intent(AppConstants.ACTION_MESSAGE_SENT);
        intent.putExtra("message_id", msgId);
        sendBroadcast(intent);

        // Press Back to return to the app they were using before WhatsApp opened
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 150);
        }, 200);
    }

    private void onMessageFailed(String error) {
        int msgId = currentMessageId;
        waitingToSend = false;
        sendAttempted = false;
        retryCount = 0;

        Intent intent = new Intent(AppConstants.ACTION_MESSAGE_FAILED);
        intent.putExtra("message_id", msgId);
        intent.putExtra("error", error);
        sendBroadcast(intent);

        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted");
        waitingToSend = false;
        sendAttempted = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Accessibility Service destroyed");
    }
}
