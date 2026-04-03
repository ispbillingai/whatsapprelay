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
 *
 * All event handling is wrapped in try-catch to prevent ANR/crash
 * which causes Android to mark the service as "not working".
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
        try {
            super.onServiceConnected();
            Log.i(TAG, "Accessibility Service connected");

            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                info.notificationTimeout = 200;
                setServiceInfo(info);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onServiceConnected: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            // Quick exit if we're not waiting to send — absolutely minimal work
            if (!waitingToSend) return;
            if (event == null) return;

            CharSequence pkg = event.getPackageName();
            if (pkg == null) return;
            String packageName = pkg.toString();

            // Only process WhatsApp events
            if (!packageName.equals(AppConstants.WHATSAPP_PACKAGE) &&
                    !packageName.equals(AppConstants.WHATSAPP_BUSINESS_PACKAGE)) {
                return;
            }

            int eventType = event.getEventType();
            if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return;
            }

            if (!sendAttempted) {
                sendAttempted = true;
                retryCount = 0;
                // Give WhatsApp time to load
                handler.postDelayed(this::safeAttemptInteraction, 500);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onAccessibilityEvent: " + e.getMessage());
        }
    }

    private void safeAttemptInteraction() {
        try {
            attemptInteraction();
        } catch (Exception e) {
            Log.e(TAG, "Error in attemptInteraction: " + e.getMessage());
            retryIfNeeded();
        }
    }

    private void attemptInteraction() {
        if (!waitingToSend) return;

        AccessibilityNodeInfo rootNode = null;
        try {
            rootNode = getRootInActiveWindow();
        } catch (Exception e) {
            Log.w(TAG, "getRootInActiveWindow error: " + e.getMessage());
        }

        if (rootNode == null) {
            retryIfNeeded();
            return;
        }

        try {
            // Step 1: Try to click "Continue to chat" or similar confirmation buttons first
            if (clickContinueButton(rootNode)) {
                Log.i(TAG, "Clicked continue/confirm button, waiting for chat to load...");
                rootNode.recycle();
                handler.postDelayed(this::safeAttemptInteraction, 2000);
                return;
            }

            // Step 2: Try to find and click the send button
            boolean sent = findAndClickSendButton(rootNode);

            if (sent) {
                Log.i(TAG, "Send button clicked for message " + currentMessageId);
                rootNode.recycle();
                onMessageSent();
            } else {
                if (retryCount == 5 || retryCount == 15) {
                    Log.w(TAG, "Send button not found on attempt " + retryCount);
                }
                rootNode.recycle();
                retryIfNeeded();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during interaction: " + e.getMessage());
            try { rootNode.recycle(); } catch (Exception ignored) {}
            retryIfNeeded();
        }
    }

    private boolean clickContinueButton(AccessibilityNodeInfo root) {
        String[] buttonTexts = {
                "Continue to chat", "CONTINUE TO CHAT",
                "Continue", "CONTINUE",
                "OK", "Ok",
        };

        try {
            for (String text : buttonTexts) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo node : nodes) {
                    try {
                        CharSequence nodeText = node.getText();
                        if (nodeText == null) continue;
                        String t = nodeText.toString().toLowerCase();

                        if (node.isClickable() && (t.contains("continue") || t.equals("ok"))) {
                            Log.i(TAG, "Clicking confirmation button: " + nodeText);
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }

                        // Try parent if node isn't clickable
                        if (t.contains("continue") || t.equals("ok")) {
                            AccessibilityNodeInfo parent = node.getParent();
                            if (parent != null && parent.isClickable()) {
                                Log.i(TAG, "Clicking parent of: " + nodeText);
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                parent.recycle();
                                return true;
                            }
                            if (parent != null) parent.recycle();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error checking button: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error in clickContinueButton: " + e.getMessage());
        }
        return false;
    }

    private boolean findAndClickSendButton(AccessibilityNodeInfo root) {
        try {
            // Log what screen we're on for debugging
            if (retryCount == 0 || retryCount == 5 || retryCount == 10 || retryCount == 20) {
                Log.i(TAG, "=== SEND BUTTON SEARCH attempt " + retryCount + " ===");
                dumpNodeTree(root, 0, 3); // dump 3 levels deep
            }

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
                        Log.i(TAG, "ID match: " + buttonId + " clickable=" + node.isClickable() + " enabled=" + node.isEnabled() + " visible=" + node.isVisibleToUser());
                        if (node.isClickable() && node.isEnabled()) {
                            Log.i(TAG, "CLICKING send button by ID: " + buttonId);
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                        // Try clicking even if not marked clickable — some WhatsApp versions
                        if (node.isEnabled() && node.isVisibleToUser()) {
                            Log.i(TAG, "CLICKING send button (not clickable flag) by ID: " + buttonId);
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                    }
                }
            }

            // Strategy 2: Find by content description containing "Send"
            if (findSendByDescription(root, 0)) {
                return true;
            }

            // Strategy 3: Find by text "Send" on clickable views
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Send");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    String className = node.getClassName() != null ? node.getClassName().toString() : "";
                    CharSequence desc = node.getContentDescription();
                    Log.i(TAG, "Text 'Send' match: class=" + className + " desc=" + desc + " clickable=" + node.isClickable() + " enabled=" + node.isEnabled());
                    if ((className.contains("ImageButton") || className.contains("ImageView"))
                            && node.isClickable() && node.isEnabled()) {
                        Log.i(TAG, "CLICKING send button by text search: " + className);
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
                // Strategy 3b: Try any clickable node with "Send" text, regardless of class
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isClickable() && node.isEnabled()) {
                        String className = node.getClassName() != null ? node.getClassName().toString() : "";
                        Log.i(TAG, "CLICKING send button (any class) by text: " + className);
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
            }

            // Strategy 4: Search all clickable nodes for send-like descriptions
            if (findSendByAnyClickable(root, 0)) {
                return true;
            }

        } catch (Exception e) {
            Log.w(TAG, "Error in findAndClickSendButton: " + e.getMessage());
        }
        return false;
    }

    /**
     * Strategy 4: Walk the full tree looking for any clickable node whose
     * content description or resource ID hints at "send".
     */
    private boolean findSendByAnyClickable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return false;
        try {
            if (node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
                String resId = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";
                String desc = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase() : "";
                if (resId.contains("send") || desc.contains("send")) {
                    Log.i(TAG, "CLICKING send (strategy 4): resId=" + resId + " desc=" + desc);
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (findSendByAnyClickable(child, depth + 1)) {
                        child.recycle();
                        return true;
                    }
                    child.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error in findSendByAnyClickable: " + e.getMessage());
        }
        return false;
    }

    /**
     * Dump the accessibility node tree for debugging.
     * Only logs on specific retry attempts to avoid log spam.
     */
    private void dumpNodeTree(AccessibilityNodeInfo node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return;
        try {
            String indent = new String(new char[depth * 2]).replace('\0', ' ');
            String className = node.getClassName() != null ? node.getClassName().toString() : "null";
            String resId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            Log.d(TAG, indent + "NODE: " + className
                    + (resId.isEmpty() ? "" : " id=" + resId)
                    + (text != null ? " text='" + text.toString().substring(0, Math.min(30, text.length())) + "'" : "")
                    + (desc != null ? " desc='" + desc + "'" : "")
                    + " click=" + node.isClickable()
                    + " enabled=" + node.isEnabled()
                    + " visible=" + node.isVisibleToUser());

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    dumpNodeTree(child, depth + 1, maxDepth);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error dumping node: " + e.getMessage());
        }
    }

    private boolean findSendByDescription(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return false;

        try {
            CharSequence desc = node.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                if (d.equals("send") || d.contains("send message") || d.equals("enviar")) {
                    if (node.isClickable()) {
                        Log.i(TAG, "CLICKING send by description: '" + desc + "'");
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                    // Try parent
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent != null && parent.isClickable()) {
                        Log.i(TAG, "CLICKING parent of send desc: '" + desc + "'");
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        parent.recycle();
                        return true;
                    }
                    if (parent != null) parent.recycle();
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (findSendByDescription(child, depth + 1)) {
                        child.recycle();
                        return true;
                    }
                    child.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error in findSendByDescription: " + e.getMessage());
        }
        return false;
    }

    private void retryIfNeeded() {
        retryCount++;
        if (retryCount < MAX_RETRY && waitingToSend) {
            handler.postDelayed(this::safeAttemptInteraction, RETRY_DELAY_MS);
        } else if (waitingToSend) {
            Log.e(TAG, "FAILED to find send button after " + MAX_RETRY + " attempts for msg " + currentMessageId);
            // Final dump of what we can see
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    Log.e(TAG, "=== FINAL NODE DUMP (failure) ===");
                    dumpNodeTree(root, 0, 5);
                    root.recycle();
                } else {
                    Log.e(TAG, "Root window is NULL at failure point");
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not dump tree at failure: " + e.getMessage());
            }
            onMessageFailed("Could not find send button after " + MAX_RETRY + " attempts");
        }
    }

    private void onMessageSent() {
        try {
            int msgId = currentMessageId;
            waitingToSend = false;
            sendAttempted = false;
            retryCount = 0;

            Log.i(TAG, "Broadcasting MESSAGE_SENT for msg " + msgId);
            Intent intent = new Intent(AppConstants.ACTION_MESSAGE_SENT);
            intent.setPackage(getPackageName());
            intent.putExtra("message_id", msgId);
            sendBroadcast(intent);

            // Press Back to return to the app they were using
            handler.postDelayed(() -> {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    handler.postDelayed(() -> {
                        try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception ignored) {}
                    }, 150);
                } catch (Exception ignored) {}
            }, 200);
        } catch (Exception e) {
            Log.e(TAG, "Error in onMessageSent: " + e.getMessage());
        }
    }

    private void onMessageFailed(String error) {
        try {
            int msgId = currentMessageId;
            waitingToSend = false;
            sendAttempted = false;
            retryCount = 0;

            Log.i(TAG, "Broadcasting MESSAGE_FAILED for msg " + msgId + ": " + error);
            Intent intent = new Intent(AppConstants.ACTION_MESSAGE_FAILED);
            intent.setPackage(getPackageName());
            intent.putExtra("message_id", msgId);
            intent.putExtra("error", error);
            sendBroadcast(intent);

            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception e) {
            Log.e(TAG, "Error in onMessageFailed: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted");
        waitingToSend = false;
        sendAttempted = false;
    }

    @Override
    public void onDestroy() {
        try {
            handler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {}
        Log.i(TAG, "Accessibility Service destroyed");
        super.onDestroy();
    }
}
