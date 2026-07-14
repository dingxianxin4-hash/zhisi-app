package com.zhisi.alert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

public class AlertService extends Service {

    private static final String TAG = "ZhisiAlert";
    private static final String CH_FOREGROUND = "zhisi_fg";
    private static final String CH_ALERT      = "zhisi_alert";
    private static final int    NOTIF_FG_ID   = 1;
    private static final int    NOTIF_ALERT_ID= 2;

    public static View statusDot = null;

    private OkHttpClient client;
    private WebSocket wsClient;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer alarmPlayer;
    private boolean isAlerting = false;
    private boolean isConnected = false;
    private int reconnectDelay = 3000;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "ZhisiApp:AlertWakeLock"
        );
        wakeLock.acquire();

        createNotificationChannels();
        startForeground(NOTIF_FG_ID, buildForegroundNotif("等待连接..."));

        client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

        connect();
    }

    private void connect() {
        Request request = new Request.Builder().url(MainActivity.WS_URL).build();
        wsClient = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                isConnected = true;
                reconnectDelay = 3000;
                mainHandler.post(() -> {
                    updateForegroundNotif("已连接 · " + MainActivity.SERVER_URL);
                    setDotColor("#22c55e");
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.getString("type");
                    if ("SYNC".equals(type)) {
                        JSONObject state = msg.getJSONObject("state");
                        handleStateSync(state);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "消息解析失败: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                isConnected = false;
                mainHandler.post(() -> {
                    updateForegroundNotif("连接断开，重连中...");
                    setDotColor("#ef4444");
                });
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                isConnected = false;
                mainHandler.post(() -> {
                    updateForegroundNotif("连接断开，重连中...");
                    setDotColor("#f59e0b");
                });
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        mainHandler.postDelayed(() -> {
            if (!isConnected) {
                connect();
                reconnectDelay = Math.min(reconnectDelay * 2, 30000);
            }
        }, reconnectDelay);
    }

    private int lastPendingCount = 0;

    private void handleStateSync(JSONObject state) {
        try {
            JSONArray alerts = state.getJSONArray("alerts");
            int pendingCount = 0;
            String firstSuiteName = "";
            String firstSuiteType = "odor";

            for (int i = 0; i < alerts.length(); i++) {
                JSONObject a = alerts.getJSONObject(i);
                if ("pend".equals(a.getString("s"))) {
                    pendingCount++;
                    if (pendingCount == 1) {
                        firstSuiteName = a.getString("suiteName");
                        String code = a.getString("suiteCode");
                        firstSuiteType = isDustSuite(code) ? "dust" : "odor";
                    }
                }
            }

            final int finalCount = pendingCount;
            final String finalName = firstSuiteName;
            final String finalType = firstSuiteType;

            mainHandler.post(() -> {
                if (finalCount > lastPendingCount) {
                    triggerAlert(finalName, finalType, finalCount);
                } else if (finalCount == 0 && isAlerting) {
                    stopAlarm();
                }
                lastPendingCount = finalCount;
            });

        } catch (Exception e) {
            Log.e(TAG, "状态解析失败: " + e.getMessage());
        }
    }

    private boolean isDustSuite(String code) {
        return "DUST".equals(code) || "ASH".equals(code) || "PACK".equals(code);
    }

    private void triggerAlert(String suiteName, String type, int count) {
        sendAlertNotification(suiteName, count);
        if (!isAlerting) {
            startAlarm(type);
        }
        vibrate();
    }

    private void sendAlertNotification(String suiteName, int count) {
        String title = count > 1
            ? "【关停提醒】共" + count + "套设备待关停"
            : "【关停提醒】" + suiteName;

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("action", "OPEN_ALERTS");
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CH_ALERT);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(android.R.drawable.ic_dialog_alert)
               .setContentTitle(title)
               .setContentText("点击查看操作步骤并确认关停")
               .setContentIntent(pi)
               .setAutoCancel(false)
               .setPriority(Notification.PRIORITY_MAX)
               .setCategory(Notification.CATEGORY_ALARM)
               .setVisibility(Notification.VISIBILITY_PUBLIC)
               .setOngoing(true)
               .setColor(Color.RED)
               .setLights(Color.RED, 500, 500);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ALERT_ID, builder.build());
    }

    private void startAlarm(String type) {
        isAlerting = true;
        try {
            if (alarmPlayer != null) {
                alarmPlayer.release();
                alarmPlayer = null;
            }
            alarmPlayer = new MediaPlayer();
            alarmPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            );
            String audioUrl = type.equals("odor")
                ? MainActivity.SERVER_URL + "/alarm_odor.mp3"
                : MainActivity.SERVER_URL + "/alarm_dust.mp3";
            try {
                alarmPlayer.setDataSource(audioUrl);
                alarmPlayer.setLooping(true);
                alarmPlayer.setVolume(1.0f, 1.0f);
                alarmPlayer.prepareAsync();
                alarmPlayer.setOnPreparedListener(mp -> mp.start());
                alarmPlayer.setOnErrorListener((mp, what, extra) -> {
                    playSystemAlarm();
                    return true;
                });
            } catch (Exception e) {
                playSystemAlarm();
            }
        } catch (Exception e) {
            playSystemAlarm();
        }
    }

    private void playSystemAlarm() {
        try {
            if (alarmPlayer != null) alarmPlayer.release();
            alarmPlayer = new MediaPlayer();
            alarmPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            );
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            alarmPlayer.setDataSource(this, alarmUri);
            alarmPlayer.setLooping(true);
            alarmPlayer.setVolume(1.0f, 1.0f);
            alarmPlayer.prepare();
            alarmPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "系统闹钟音失败: " + e.getMessage());
        }
    }

    private void stopAlarm() {
        isAlerting = false;
        if (alarmPlayer != null) {
            try {
                if (alarmPlayer.isPlaying()) alarmPlayer.stop();
                alarmPlayer.release();
            } catch (Exception e) { }
            alarmPlayer = null;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ALERT_ID);
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = {0, 200, 100, 200, 100, 500};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel fg = new NotificationChannel(
            CH_FOREGROUND, "服务运行状态", NotificationManager.IMPORTANCE_LOW
        );
        nm.createNotificationChannel(fg);

        NotificationChannel alert = new NotificationChannel(
            CH_ALERT, "关停提醒", NotificationManager.IMPORTANCE_HIGH
        );
        alert.enableLights(true);
        alert.setLightColor(Color.RED);
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 200, 100, 200, 100, 500});
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alert.setSound(null, null);
        nm.createNotificationChannel(alert);
    }

    private void updateForegroundNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_FG_ID, buildForegroundNotif(text));
    }

    private Notification buildForegroundNotif(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_FOREGROUND);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("制丝车间管控系统")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void setDotColor(final String hexColor) {
        if (statusDot == null) return;
        statusDot.post(() -> {
            if (statusDot != null && statusDot.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) statusDot.getBackground()).setColor(Color.parseColor(hexColor));
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarm();
        if (wsClient != null) wsClient.close(1000, "Service销毁");
        if (client != null) client.dispatcher().executorService().shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
