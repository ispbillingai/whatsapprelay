package com.freeisp.wa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Restarts the polling service after device reboot if it was previously running.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(
                    AppConstants.PREFS_NAME, Context.MODE_PRIVATE);

            if (prefs.getBoolean(AppConstants.PREF_SERVICE_RUNNING, false)) {
                Log.i("BootReceiver", "Restarting relay service after boot");
                Intent serviceIntent = new Intent(context, PollingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
