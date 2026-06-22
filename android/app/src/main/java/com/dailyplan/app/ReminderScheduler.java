package com.dailyplan.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public final class ReminderScheduler {
    static final String CHANNEL_ID = "daily_plan_reminders";
    private static final String PREFS = "daily_plan_native_reminders";
    private static final String TASKS_JSON = "tasks_json";
    private static final String REQUEST_CODES = "request_codes";

    private ReminderScheduler() {
    }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "日程提醒",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("按计划时间提醒待办事项");
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    public static void syncTasks(Context context, String tasksJson) {
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(TASKS_JSON, tasksJson).apply();
        scheduleAll(appContext, tasksJson);
    }

    public static void restore(Context context) {
        String tasksJson = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(TASKS_JSON, "[]");
        scheduleAll(context.getApplicationContext(), tasksJson);
    }

    private static void scheduleAll(Context context, String tasksJson) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> oldCodes = prefs.getStringSet(REQUEST_CODES, new HashSet<>());
        for (String code : oldCodes) {
            cancelAlarm(context, alarmManager, Integer.parseInt(code));
        }

        Set<String> newCodes = new HashSet<>();
        try {
            JSONArray tasks = new JSONArray(tasksJson);
            long now = System.currentTimeMillis();
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                if (task.optBoolean("done", false)) continue;
                String time = task.optString("time", "");
                String[] parts = time.split(":");
                if (parts.length != 2) continue;

                Calendar due = Calendar.getInstance();
                due.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                due.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                due.set(Calendar.SECOND, 0);
                due.set(Calendar.MILLISECOND, 0);
                if (due.getTimeInMillis() <= now) continue;

                long taskId = task.optLong("id", i + 1L);
                int requestCode = (int) (taskId ^ (taskId >>> 32));
                Intent intent = new Intent(context, AlarmReceiver.class)
                        .putExtra("title", task.optString("title", "日程提醒"))
                        .putExtra("time", time)
                        .putExtra("notification_id", requestCode);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.getTimeInMillis(), pendingIntent);
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.getTimeInMillis(), pendingIntent);
                }
                newCodes.add(String.valueOf(requestCode));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putStringSet(REQUEST_CODES, newCodes).apply();
    }

    private static void cancelAlarm(Context context, AlarmManager alarmManager, int requestCode) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                new Intent(context, AlarmReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }
}
