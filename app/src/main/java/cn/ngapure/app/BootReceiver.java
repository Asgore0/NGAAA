package cn.ngapure.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the existing user preference after reboot or an application update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            CheckInService.schedule(context);
            NativeNotifications.schedule(context);
        }
    }
}
