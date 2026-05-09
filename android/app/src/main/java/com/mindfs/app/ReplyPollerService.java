package com.mindfs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.TextUtils;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class ReplyPollerService extends Service {
    static final String ACTION_CONFIGURE = "com.mindfs.app.reply_poller.CONFIGURE";
    static final String ACTION_RESUME = "com.mindfs.app.reply_poller.RESUME";
    static final String ACTION_PAUSE = "com.mindfs.app.reply_poller.PAUSE";
    static final String ACTION_CLEAR_COMPLETED = "com.mindfs.app.reply_poller.CLEAR_COMPLETED";
    static final String ACTION_STOP = "com.mindfs.app.reply_poller.STOP";
    static final String EXTRA_API_BASE_URL = "apiBaseUrl";
    static final String EXTRA_TOKEN = "token";

    private static final String TAG = "MindFSReplyPoller";
    private static final String PREFS_NAME = "mindfs_reply_poller";
    private static final String PREF_API_BASE_URL = "api_base_url";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_COMPLETED_KEYS = "completed_keys";
    private static final String PROGRESS_CHANNEL_ID = "mindfs_reply_progress_v1";
    private static final String VISIBLE_PROGRESS_CHANNEL_ID = "mindfs_reply_progress_visible_v1";
    private static final String ALERT_CHANNEL_ID = "mindfs_reply_alert_v1";
    private static final String REPLY_GROUP_KEY = "com.mindfs.app.REPLY_NOTIFICATIONS";
    private static final int FOREGROUND_ID = 750001;
    private static final int NOTIFICATION_BASE_ID = 760000;
    private static final long ACTIVE_POLL_INTERVAL_SECONDS = 5;
    private static final long IDLE_POLL_INTERVAL_SECONDS = 15;

    private final Map<String, ReplyState> states = new HashMap<>();
    private final Map<Integer, String> notificationSignatures = new HashMap<>();
    private NotificationManager notificationManager;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pollingTask;
    private volatile boolean running = false;
    private String apiBaseUrl = "";
    private String token = "";
    private NotificationMode notificationMode = NotificationMode.NONE;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        executor = Executors.newSingleThreadScheduledExecutor();
        createChannels();
        loadConfig();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CONFIGURE.equals(action)) {
            configure(intent);
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumePolling();
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pausePolling();
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR_COMPLETED.equals(action)) {
            clearCompleted();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPollingTask();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private void configure(Intent intent) {
        apiBaseUrl = safe(intent.getStringExtra(EXTRA_API_BASE_URL)).replaceAll("/+$", "");
        token = safe(intent.getStringExtra(EXTRA_TOKEN));
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(PREF_API_BASE_URL, apiBaseUrl);
        editor.putString(PREF_TOKEN, token);
        editor.apply();
        Log.i(TAG, "configured apiBaseUrl=" + apiBaseUrl);
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        apiBaseUrl = safe(prefs.getString(PREF_API_BASE_URL, ""));
        token = safe(prefs.getString(PREF_TOKEN, ""));
    }

    private void resumePolling() {
        loadConfig();
        if (apiBaseUrl.isEmpty()) {
            Log.w(TAG, "skip resume: apiBaseUrl is empty");
            stopSelf();
            return;
        }
        Log.i(TAG, "resume polling apiBaseUrl=" + apiBaseUrl);
        running = true;
        startForegroundCompat(FOREGROUND_ID, buildReplyPlaceholderNotification());
        scheduleNextPoll(0);
    }

    private void pausePolling() {
        Log.i(TAG, "pause polling");
        running = false;
        stopPollingTask();
        stopForegroundCompat();
        stopSelf();
    }

    private void stopAll() {
        running = false;
        stopPollingTask();
        for (ReplyState state : states.values()) {
            notificationManager.cancel(state.notificationId);
        }
        for (String sessionKey : getCompletedKeys()) {
            notificationManager.cancel(notificationIdFor(sessionKey));
        }
        notificationManager.cancel(FOREGROUND_ID);
        notificationSignatures.clear();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_COMPLETED_KEYS).apply();
        states.clear();
        notificationMode = NotificationMode.NONE;
        stopForegroundCompat();
        stopSelf();
    }

    private void stopPollingTask() {
        if (pollingTask != null) {
            pollingTask.cancel(true);
            pollingTask = null;
        }
    }

    private void pollOnceSafely() {
        if (!running) {
            return;
        }
        long nextDelaySeconds = IDLE_POLL_INTERVAL_SECONDS;
        try {
            nextDelaySeconds = applyReplyingSessions(fetchReplyingSessions());
        } catch (Exception ex) {
            Log.w(TAG, "poll failed", ex);
            nextDelaySeconds = IDLE_POLL_INTERVAL_SECONDS;
        } finally {
            scheduleNextPoll(nextDelaySeconds);
        }
    }

    private void scheduleNextPoll(long delaySeconds) {
        if (!running || executor == null || executor.isShutdown()) {
            return;
        }
        if (pollingTask != null && !pollingTask.isDone() && !pollingTask.isCancelled()) {
            pollingTask.cancel(false);
        }
        pollingTask = executor.schedule(this::pollOnceSafely, delaySeconds, TimeUnit.SECONDS);
    }

    private Map<String, ReplyState> fetchReplyingSessions() throws Exception {
        URL url = new URL(apiBaseUrl + "/api/replying-sessions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(stream);
        conn.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }

        JSONObject root = new JSONObject(body);
        JSONArray sessions = root.optJSONArray("sessions");
        Map<String, ReplyState> next = new HashMap<>();
        if (sessions == null) {
            return next;
        }
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String sessionKey = safe(item.optString("sessionKey", ""));
            if (sessionKey.isEmpty()) {
                continue;
            }
            ReplyState state = new ReplyState(sessionKey);
            state.rootId = safe(item.optString("rootId", ""));
            state.rootTitle = safe(item.optString("rootTitle", ""));
            state.sessionTitle = safe(item.optString("sessionTitle", ""));
            state.summary = safe(item.optString("summary", ""));
            state.updatedAt = safe(item.optString("updatedAt", ""));
            state.status = ReplyStatus.REPLYING;
            Log.i(TAG, "fetched session=" + sessionKey + " summaryLength=" + state.summary.length());
            next.put(sessionKey, state);
        }
        return next;
    }

    private long applyReplyingSessions(Map<String, ReplyState> next) {
        Log.i(TAG, "poll success sessions=" + next.size());
        Set<String> currentKeys = new HashSet<>(next.keySet());
        for (ReplyState incoming : next.values()) {
            ReplyState state = states.get(incoming.sessionKey);
            if (state == null) {
                state = incoming;
                states.put(state.sessionKey, state);
            } else {
                boolean wasCompleted = state.status == ReplyStatus.COMPLETED;
                state.rootId = incoming.rootId;
                state.rootTitle = incoming.rootTitle;
                state.sessionTitle = incoming.sessionTitle;
                if (wasCompleted || !incoming.summary.isEmpty()) {
                    state.summary = incoming.summary;
                }
                state.updatedAt = incoming.updatedAt;
                state.status = ReplyStatus.REPLYING;
            }
            removeCompletedKey(state.sessionKey);
        }

        for (ReplyState state : states.values()) {
            if (state.status == ReplyStatus.REPLYING && !currentKeys.contains(state.sessionKey)) {
                state.status = ReplyStatus.COMPLETED;
                addCompletedKey(state.sessionKey);
                vibrateOnce();
            }
        }

        List<ReplyState> visibleStates = visibleStates();
        if (currentKeys.isEmpty()) {
            if (!visibleStates.isEmpty()) {
                showReplyNotifications(visibleStates);
                stopForegroundDetachCompat();
            } else {
                stopForegroundCompat();
            }
            return IDLE_POLL_INTERVAL_SECONDS;
        } else {
            showReplyNotifications(visibleStates);
            return ACTIVE_POLL_INTERVAL_SECONDS;
        }
    }

    private void showReplyNotifications(List<ReplyState> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        sortMostRecentFirst(states);
        if (states.size() == 1) {
            showSingleReplyNotification(states.get(0));
            return;
        }
        boolean hasReplying = hasReplyingState(states);
        if (notificationMode != NotificationMode.GROUP) {
            notificationManager.cancel(FOREGROUND_ID);
            notificationSignatures.remove(FOREGROUND_ID);
        }
        notificationMode = NotificationMode.GROUP;
        Notification summary = buildReplyGroupSummaryNotification(states);
        boolean foregroundStarted = false;
        if (!hasReplying) {
            notifyIfChanged(FOREGROUND_ID, summary, buildGroupSummarySignature(states));
        } else {
            try {
                startForegroundIfChanged(FOREGROUND_ID, summary, buildGroupSummarySignature(states));
                foregroundStarted = true;
            } catch (RuntimeException ex) {
                Log.w(TAG, "start foreground failed, posting notification only", ex);
                notifyIfChanged(FOREGROUND_ID, summary, buildGroupSummarySignature(states));
            }
        }
        for (ReplyState state : states) {
            notifyIfChanged(state.notificationId, buildReplyChildNotification(state), buildStateSignature(state, true));
        }
        if (!foregroundStarted && hasReplying) {
            notifyIfChanged(FOREGROUND_ID, summary, buildGroupSummarySignature(states));
        }
    }

    private void showSingleReplyNotification(ReplyState state) {
        if (notificationMode != NotificationMode.SINGLE) {
            for (ReplyState item : states.values()) {
                notificationManager.cancel(item.notificationId);
                notificationSignatures.remove(item.notificationId);
            }
        }
        notificationMode = NotificationMode.SINGLE;
        boolean replying = state.status == ReplyStatus.REPLYING;
        Notification notification = buildReplyNotification(state);
        String signature = buildStateSignature(state, false);
        if (!replying) {
            notifyIfChanged(FOREGROUND_ID, notification, signature);
            return;
        }
        try {
            startForegroundIfChanged(FOREGROUND_ID, notification, signature);
        } catch (RuntimeException ex) {
            Log.w(TAG, "start foreground failed, posting notification only", ex);
            notifyIfChanged(FOREGROUND_ID, notification, signature);
        }
    }

    private void notifyIfChanged(int notificationId, Notification notification, String signature) {
        if (signature.equals(notificationSignatures.get(notificationId))) {
            return;
        }
        notificationManager.notify(notificationId, notification);
        notificationSignatures.put(notificationId, signature);
    }

    private void startForegroundIfChanged(int notificationId, Notification notification, String signature) {
        if (signature.equals(notificationSignatures.get(notificationId))) {
            return;
        }
        startForegroundCompat(notificationId, notification);
        notificationSignatures.put(notificationId, signature);
    }

    private String buildGroupSummarySignature(List<ReplyState> states) {
        StringBuilder builder = new StringBuilder(hasReplyingState(states) ? "group:replying" : "group:done");
        builder.append(':').append(states.size());
        for (ReplyState state : states) {
            builder.append('|').append(buildStateSignature(state, true));
        }
        return builder.toString();
    }

    private String buildStateSignature(ReplyState state, boolean grouped) {
        return (grouped ? "child" : "single") +
            ":" + state.sessionKey +
            ":" + state.rootId +
            ":" + state.rootTitle +
            ":" + state.sessionTitle +
            ":" + state.summary +
            ":" + state.status.name();
    }

    private List<ReplyState> visibleStates() {
        List<ReplyState> items = new ArrayList<>();
        for (ReplyState state : states.values()) {
            if (state.status == ReplyStatus.REPLYING || state.status == ReplyStatus.COMPLETED) {
                items.add(state);
            }
        }
        return items;
    }

    private void sortMostRecentFirst(List<ReplyState> items) {
        Collections.sort(items, new Comparator<ReplyState>() {
            @Override
            public int compare(ReplyState left, ReplyState right) {
                return right.updatedAt.compareTo(left.updatedAt);
            }
        });
    }

    private boolean hasReplyingState(List<ReplyState> states) {
        for (ReplyState state : states) {
            if (state.status == ReplyStatus.REPLYING) {
                return true;
            }
        }
        return false;
    }

    private Notification buildReplyGroupSummaryNotification(List<ReplyState> states) {
        ReplyState target = states.get(0);
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent == null) {
            launchIntent = new Intent(this, MainActivity.class);
        }
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra("rootId", target.rootId);
        launchIntent.putExtra("sessionKey", target.sessionKey);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            FOREGROUND_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        boolean hasReplying = hasReplyingState(states);
        String status = hasReplying ? "回复中" : "回复完成";
        String title = "MindFS";
        String text = buildAggregateLine(target);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, hasReplying ? VISIBLE_PROGRESS_CHANNEL_ID : ALERT_CHANNEL_ID)
            : new Notification.Builder(this);
        builder
            .setSmallIcon(R.drawable.ic_stat_mindfs_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(hasReplying)
            .setOnlyAlertOnce(hasReplying)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(hasReplying ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_STATUS)
            .setGroup(REPLY_GROUP_KEY)
            .setGroupSummary(true)
            .setStyle(new Notification.InboxStyle().setSummaryText(status));
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (ReplyState state : states) {
            style.addLine(buildAggregateLine(state));
        }
        style.setSummaryText(status);
        builder.setStyle(style);
        if (!TextUtils.isEmpty(text)) {
            builder.setTicker(text);
        }
        return builder.build();
    }

    private String buildAggregateText(List<ReplyState> states) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(buildAggregateLine(states.get(i)));
        }
        return builder.toString();
    }

    private String buildAggregateLine(ReplyState state) {
        String title = buildReplyTitle(state, state.status == ReplyStatus.COMPLETED ? "完成" : "回复中");
        if (state.summary.isEmpty()) {
            return title;
        }
        return title + ": " + state.summary;
    }

    private Notification buildReplyPlaceholderNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, VISIBLE_PROGRESS_CHANNEL_ID)
            : new Notification.Builder(this);
        builder
            .setSmallIcon(R.drawable.ic_stat_mindfs_notification)
            .setContentTitle("MindFS")
            .setContentText("正在检查后台回复")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private Notification buildReplyNotification(ReplyState state) {
        boolean replying = state.status == ReplyStatus.REPLYING;
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent == null) {
            launchIntent = new Intent(this, MainActivity.class);
        }
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra("rootId", state.rootId);
        launchIntent.putExtra("sessionKey", state.sessionKey);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            state.notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = buildReplyTitle(state, replying ? "回复中" : "完成");
        String text = state.summary;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, replying ? VISIBLE_PROGRESS_CHANNEL_ID : ALERT_CHANNEL_ID)
            : new Notification.Builder(this);
        builder
            .setSmallIcon(R.drawable.ic_stat_mindfs_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(replying)
            .setOnlyAlertOnce(replying)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(replying ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_STATUS)
            .setStyle(new Notification.BigTextStyle().bigText(text));
        if (!TextUtils.isEmpty(text)) {
            builder.setTicker(text);
        }
        return builder.build();
    }

    private Notification buildReplyChildNotification(ReplyState state) {
        boolean replying = state.status == ReplyStatus.REPLYING;
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent == null) {
            launchIntent = new Intent(this, MainActivity.class);
        }
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra("rootId", state.rootId);
        launchIntent.putExtra("sessionKey", state.sessionKey);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            state.notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = buildReplyTitle(state, replying ? "回复中" : "完成");
        String text = state.summary;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, replying ? VISIBLE_PROGRESS_CHANNEL_ID : ALERT_CHANNEL_ID)
            : new Notification.Builder(this);
        builder
            .setSmallIcon(R.drawable.ic_stat_mindfs_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(replying)
            .setOnlyAlertOnce(replying)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(replying ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_STATUS)
            .setGroup(REPLY_GROUP_KEY)
            .setGroupSummary(false)
            .setStyle(new Notification.BigTextStyle().bigText(text));
        if (!TextUtils.isEmpty(text)) {
            builder.setTicker(text);
        }
        return builder.build();
    }

    private String buildReplyTitle(ReplyState state, String status) {
        return buildReplyTitlePrefix(state) + " · " + status;
    }

    private String buildReplyTitlePrefix(ReplyState state) {
        String root = state.rootTitle.isEmpty() ? state.rootId : state.rootTitle;
        if (root.isEmpty()) {
            root = "MindFS";
        }
        String title = state.sessionTitle.isEmpty() ? "会话" : state.sessionTitle;
        return root + " · " + title;
    }

    private String buildReplyItemTitle(ReplyState state, String status) {
        String title = state.sessionTitle.isEmpty() ? "会话" : state.sessionTitle;
        return title + " · " + status;
    }

    private void clearCompleted() {
        Iterator<Map.Entry<String, ReplyState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ReplyState> entry = iterator.next();
            ReplyState state = entry.getValue();
            if (state.status == ReplyStatus.COMPLETED) {
                notificationManager.cancel(state.notificationId);
                iterator.remove();
            }
        }
        Set<String> completedKeys = getCompletedKeys();
        for (String sessionKey : completedKeys) {
            notificationManager.cancel(notificationIdFor(sessionKey));
        }
        notificationManager.cancel(FOREGROUND_ID);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_COMPLETED_KEYS).apply();
    }

    private Set<String> getCompletedKeys() {
        return new HashSet<>(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getStringSet(PREF_COMPLETED_KEYS, new HashSet<>()));
    }

    private void addCompletedKey(String sessionKey) {
        Set<String> keys = getCompletedKeys();
        keys.add(sessionKey);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putStringSet(PREF_COMPLETED_KEYS, keys).apply();
    }

    private void removeCompletedKey(String sessionKey) {
        Set<String> keys = getCompletedKeys();
        if (!keys.remove(sessionKey)) {
            return;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putStringSet(PREF_COMPLETED_KEYS, keys).apply();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }
        NotificationChannel progress = new NotificationChannel(
            PROGRESS_CHANNEL_ID,
            "MindFS reply progress",
            NotificationManager.IMPORTANCE_LOW
        );
        progress.setDescription("MindFS background reply progress");
        progress.setSound(null, null);
        progress.enableVibration(false);
        progress.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(progress);

        NotificationChannel visibleProgress = new NotificationChannel(
            VISIBLE_PROGRESS_CHANNEL_ID,
            "MindFS reply progress",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        visibleProgress.setDescription("MindFS background reply progress");
        visibleProgress.setSound(null, null);
        visibleProgress.enableVibration(false);
        visibleProgress.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(visibleProgress);

        NotificationChannel alert = new NotificationChannel(
            ALERT_CHANNEL_ID,
            "MindFS reply alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        alert.setDescription("MindFS reply completion alerts");
        alert.enableVibration(true);
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(alert);
    }

    private void startForegroundCompat(int notificationId, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(notificationId, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void stopForegroundDetachCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void vibrateOnce() {
        Vibrator vibrator = getReplyVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(180);
        }
    }

    private Vibrator getReplyVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                return manager.getDefaultVibrator();
            }
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static int notificationIdFor(String sessionKey) {
        return NOTIFICATION_BASE_ID + Math.abs(sessionKey.hashCode() % 100000);
    }

    private enum ReplyStatus {
        REPLYING,
        COMPLETED
    }

    private enum NotificationMode {
        NONE,
        SINGLE,
        GROUP
    }

    private static final class ReplyState {
        final String sessionKey;
        final int notificationId;
        String rootId = "";
        String rootTitle = "";
        String sessionTitle = "";
        String summary = "";
        String updatedAt = "";
        ReplyStatus status = ReplyStatus.REPLYING;

        ReplyState(String sessionKey) {
            this.sessionKey = sessionKey;
            this.notificationId = notificationIdFor(sessionKey);
        }
    }
}
