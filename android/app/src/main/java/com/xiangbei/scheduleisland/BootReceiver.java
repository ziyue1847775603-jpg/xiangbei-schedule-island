package com.xiangbei.scheduleisland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        boolean enabled = context.getSharedPreferences("island", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (!enabled || !Settings.canDrawOverlays(context)) return;
        Intent service = new Intent(context, IslandService.class).setAction(IslandService.ACTION_START);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException ignored) {
            // Some vendor systems require the user to whitelist autostart/background activity.
        }
    }
}
