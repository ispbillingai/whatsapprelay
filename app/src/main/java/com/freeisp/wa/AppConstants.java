package com.freeisp.wa;

public class AppConstants {
    // SharedPreferences
    public static final String PREFS_NAME = "wa_relay_prefs";
    public static final String PREF_SERVER_URL = "server_url";
    public static final String PREF_API_KEY = "api_key";
    public static final String PREF_POLL_INTERVAL = "poll_interval";
    public static final String PREF_WHATSAPP_TYPE = "whatsapp_type";
    public static final String PREF_SERVICE_RUNNING = "service_running";

    // WhatsApp package names
    public static final String WHATSAPP_PACKAGE = "com.whatsapp";
    public static final String WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b";

    // Notification channels
    public static final String CHANNEL_SERVICE = "relay_service";
    public static final String CHANNEL_MESSAGES = "relay_messages";

    // Notification IDs
    public static final int NOTIFICATION_SERVICE = 1001;
    public static final int NOTIFICATION_MESSAGE = 2000;

    // Broadcast actions
    public static final String ACTION_MESSAGE_SENT = "com.freeisp.wa.MESSAGE_SENT";
    public static final String ACTION_MESSAGE_FAILED = "com.freeisp.wa.MESSAGE_FAILED";
    public static final String ACTION_SERVICE_STATUS = "com.freeisp.wa.SERVICE_STATUS";
    public static final String ACTION_STATS_UPDATED = "com.freeisp.wa.STATS_UPDATED";
    public static final String ACTION_CACHE_UPDATED = "com.freeisp.wa.CACHE_UPDATED";

    // Default polling interval in seconds
    public static final int DEFAULT_POLL_INTERVAL = 5;
}
