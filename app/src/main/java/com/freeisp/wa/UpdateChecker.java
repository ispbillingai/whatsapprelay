package com.freeisp.wa;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks for app updates from the server and prompts the user to download.
 *
 * Server endpoint: {base_url}/version.json
 * Expected response: { "versionCode": 2, "versionName": "1.1.0", "apkUrl": "...", "changelog": "..." }
 */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";

    private final Context context;
    private final OkHttpClient client = new OkHttpClient();
    private boolean showUpToDateMessage = false;

    public UpdateChecker(Context context) {
        this.context = context;
    }

    /**
     * Build the version.json URL from the server URL entered in app settings.
     */
    private String getVersionEndpoint() {
        String serverUrl = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AppConstants.PREF_SERVER_URL, "");
        if (serverUrl.isEmpty()) return "";
        // Remove trailing slash and api.php if present
        serverUrl = serverUrl.replaceAll("/+$", "");
        if (serverUrl.endsWith("/api.php")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 8);
        }
        return serverUrl + "/version.json";
    }

    /**
     * Check for updates in background, show dialog if update available.
     * Call this from Activity (needs UI context for dialog).
     */
    public void checkForUpdate() {
        showUpToDateMessage = true;
        checkForUpdateSilent();
    }

    /**
     * Silent check - only shows dialog if update is available (used on app launch).
     */
    public void checkForUpdateSilent() {
        new Thread(() -> {
            try {
                String endpoint = getVersionEndpoint();
                if (endpoint.isEmpty()) {
                    if (showUpToDateMessage) showMessage("Set server URL first");
                    return;
                }

                Request request = new Request.Builder()
                        .url(endpoint)
                        .build();

                Response response = client.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    if (showUpToDateMessage) showMessage("Could not reach update server");
                    return;
                }

                String json = response.body().string();
                JSONObject obj = new JSONObject(json);

                int remoteVersionCode = obj.getInt("versionCode");
                String remoteVersionName = obj.optString("versionName", "");
                String apkUrl = obj.optString("apkUrl", "");
                String changelog = obj.optString("changelog", "Bug fixes and improvements");

                int currentVersionCode = getCurrentVersionCode();

                if (remoteVersionCode > currentVersionCode && !apkUrl.isEmpty()) {
                    Log.i(TAG, "Update available: " + currentVersionCode + " -> " + remoteVersionCode);
                    showUpdateDialog(remoteVersionName, changelog, apkUrl);
                } else {
                    Log.i(TAG, "App is up to date (v" + currentVersionCode + ")");
                    if (showUpToDateMessage) showMessage("You're up to date! (v" + getCurrentVersionName() + ")");
                }

            } catch (Exception e) {
                Log.w(TAG, "Update check failed: " + e.getMessage());
                if (showUpToDateMessage) showMessage("Update check failed: " + e.getMessage());
            }
        }).start();
    }

    private String getCurrentVersionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void showMessage(String msg) {
        if (!(context instanceof android.app.Activity)) return;
        android.app.Activity activity = (android.app.Activity) context;
        activity.runOnUiThread(() -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 1;
        }
    }

    private void showUpdateDialog(String versionName, String changelog, String apkUrl) {
        if (!(context instanceof android.app.Activity)) return;
        android.app.Activity activity = (android.app.Activity) context;

        activity.runOnUiThread(() -> {
            new AlertDialog.Builder(context)
                    .setTitle("Update Available - v" + versionName)
                    .setMessage(changelog + "\n\nWould you like to download and install the update?")
                    .setPositiveButton("Update Now", (d, w) -> downloadAndInstall(apkUrl))
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show();
        });
    }

    private static final String APK_FILENAME = "freeisp-wa-update.apk";

    private void downloadAndInstall(String apkUrl) {
        try {
            // Delete any old update file first to prevent installing stale APK
            File oldFile = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), APK_FILENAME);
            if (oldFile.exists()) {
                boolean deleted = oldFile.delete();
                Log.i(TAG, "Deleted old update file: " + deleted);
            }

            Uri uri = Uri.parse(apkUrl);

            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle("FreeISP WA Update");
            request.setDescription("Downloading update...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILENAME);
            request.setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = dm.enqueue(request);

            // Register receiver to install after download completes
            BroadcastReceiver onComplete = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        installDownloadedApk(dm, downloadId);
                        try {
                            context.unregisterReceiver(this);
                        } catch (Exception ignored) {}
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(onComplete,
                        new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(onComplete,
                        new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            }

            Log.i(TAG, "Download started for: " + apkUrl);

        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            // Fallback: open in browser
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browserIntent);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Install the APK using the DownloadManager's URI directly.
     * This ensures we install the exact file that was just downloaded,
     * not a stale file from a previous download.
     */
    private void installDownloadedApk(DownloadManager dm, long downloadId) {
        try {
            // Get the actual downloaded file URI from DownloadManager
            Uri downloadedUri = dm.getUriForDownloadedFile(downloadId);

            if (downloadedUri != null) {
                Log.i(TAG, "Installing from DownloadManager URI: " + downloadedUri);
                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                installIntent.setDataAndType(downloadedUri, "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(installIntent);
                return;
            }

            // Fallback: use file path
            Log.w(TAG, "DownloadManager URI null, falling back to file path");
            File file = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), APK_FILENAME);

            if (!file.exists()) {
                Log.e(TAG, "Downloaded APK file not found at: " + file.getAbsolutePath());
                return;
            }

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri contentUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider", file);
                installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                installIntent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            }

            context.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
        }
    }
}
