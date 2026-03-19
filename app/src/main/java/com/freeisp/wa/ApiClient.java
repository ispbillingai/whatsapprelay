package com.freeisp.wa;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Handles all communication with the PHP relay server.
 */
public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Gson gson;
    private final Context context;

    public ApiClient(Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String getServerUrl() {
        String url = getPrefs().getString(AppConstants.PREF_SERVER_URL, "");
        // Remove trailing slash
        url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        // Ensure URL points to api.php
        if (!url.endsWith("api.php")) {
            url = url + "/api.php";
        }
        return url;
    }

    private String getApiKey() {
        return getPrefs().getString(AppConstants.PREF_API_KEY, "");
    }

    /**
     * Test connection to the server
     */
    public ApiResult testConnection() {
        try {
            Request request = new Request.Builder()
                    .url(getServerUrl() + "/health")
                    .addHeader("X-API-Key", getApiKey())
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                String contentType = response.header("Content-Type", "");
                if (response.isSuccessful() && contentType.contains("application/json")) {
                    return new ApiResult(true, "Connected! Server is healthy.\nServer time: " + body);
                } else if (response.isSuccessful()) {
                    return new ApiResult(false, "Server responded but returned HTML instead of JSON.\nMake sure the URL points to api.php\nURL used: " + getServerUrl() + "/health");
                } else {
                    return new ApiResult(false, "Server responded with HTTP " + response.code());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Connection test failed", e);
            return new ApiResult(false, "Connection failed: " + e.getMessage());
        }
    }

    /**
     * Fetch pending messages from server
     */
    public List<MessageModel> fetchPendingMessages() {
        try {
            Request request = new Request.Builder()
                    .url(getServerUrl() + "/pending?limit=2")
                    .addHeader("X-API-Key", getApiKey())
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    JsonObject json = gson.fromJson(body, JsonObject.class);

                    if (json.has("messages")) {
                        Type listType = new TypeToken<List<MessageModel>>() {}.getType();
                        return gson.fromJson(json.get("messages"), listType);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch messages", e);
        }
        return new ArrayList<>();
    }

    /**
     * Report message delivery status back to server
     */
    public boolean reportStatus(int messageId, String status, String errorMessage) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("message_id", messageId);
            json.addProperty("status", status);
            if (errorMessage != null) {
                json.addProperty("error_message", errorMessage);
            }

            RequestBody body = RequestBody.create(gson.toJson(json), JSON);

            Request request = new Request.Builder()
                    .url(getServerUrl() + "/status")
                    .addHeader("X-API-Key", getApiKey())
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to report status for message " + messageId, e);
            return false;
        }
    }

    /**
     * Simple result wrapper
     */
    public static class ApiResult {
        public final boolean success;
        public final String message;

        public ApiResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
