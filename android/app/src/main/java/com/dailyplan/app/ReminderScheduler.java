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
    private static final int MORNING_REQUEST_CODE = 820001;
    private static final int REVIEW_REQUEST_CODE = 213001;

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
            JSONArray tasks;
            boolean notificationsEnabled = true;
            String trimmed = tasksJson == null ? "[]" : tasksJson.trim();
            if (trimmed.startsWith("{")) {
                JSONObject payload = new JSONObject(trimmed);
                notificationsEnabled = payload.optBoolean("notifications", true);
                tasks = payload.optJSONArray("tasks");
                if (tasks == null) tasks = new JSONArray();
            } else {
                tasks = new JSONArray(trimmed);
            }
            if (!notificationsEnabled) {
                prefs.edit().putStringSet(REQUEST_CODES, newCodes).apply();
                return;
            }
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
                int requestCode = requestCodeForTask(taskId);
                Intent intent = new Intent(context, AlarmReceiver.class)
                        .putExtra("title", task.optString("title", "日程提醒"))
                        .putExtra("time", time)
                        .putExtra("body", "该处理这项工作了")
                        .putExtra("notification_id", requestCode);
                scheduleAlarm(context, alarmManager, requestCode, due.getTimeInMillis(), intent);
                newCodes.add(String.valueOf(requestCode));
            }
            scheduleDailyNudge(context, alarmManager, MORNING_REQUEST_CODE, 8, 30, "查看今日安排", "早上好，先看一眼今天最重要的事");
            scheduleDailyNudge(context, alarmManager, REVIEW_REQUEST_CODE, 21, 30, "填写今日复盘", "花一分钟收尾，顺手安排明天第一件事");
            newCodes.add(String.valueOf(MORNING_REQUEST_CODE));
            newCodes.add(String.valueOf(REVIEW_REQUEST_CODE));
        } catch (Exception ignored) {
        }
        prefs.edit().putStringSet(REQUEST_CODES, newCodes).apply();
    }

    private static int requestCodeForTask(long taskId) {
        return 100000 + Math.abs((int) (taskId ^ (taskId >>> 32)));
    }

    private static void scheduleDailyNudge(Context context, AlarmManager alarmManager, int requestCode, int hour, int minute, String title, String body) {
        Calendar due = Calendar.getInstance();
        due.set(Calendar.HOUR_OF_DAY, hour);
        due.set(Calendar.MINUTE, minute);
        due.set(Calendar.SECOND, 0);
        due.set(Calendar.MILLISECOND, 0);
        if (due.getTimeInMillis() <= System.currentTimeMillis()) {
            due.add(Calendar.DAY_OF_YEAR, 1);
        }
        Intent intent = new Intent(context, AlarmReceiver.class)
                .putExtra("title", title)
                .putExtra("time", String.format("%02d:%02d", hour, minute))
                .putExtra("body", body)
                .putExtra("daily", true)
                .putExtra("notification_id", requestCode);
        scheduleAlarm(context, alarmManager, requestCode, due.getTimeInMillis(), intent);
    }

    private static void scheduleAlarm(Context context, AlarmManager alarmManager, int requestCode, long triggerAtMillis, Intent intent) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
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
