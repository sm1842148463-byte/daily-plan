package com.dailyplan.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

public class ReminderBridge {
    private final Context context;

    ReminderBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void syncTasks(String tasksJson) {
        ReminderScheduler.syncTasks(context, tasksJson);
    }

    @JavascriptInterface
    public String getAppVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception ignored) {
            return "";
        }
    }
}
