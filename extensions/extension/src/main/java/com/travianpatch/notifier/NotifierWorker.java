package com.travianpatch.notifier;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * One background check: resumes the game's own login session (see
 * TravianSession), polls for build/troop queues, and fires a local
 * notification for anything that finished since the last check.
 *
 * Runs on two triggers, both plain WorkManager (no foreground service, no
 * persistent notification): a periodic safety net every ~15 minutes (the
 * platform minimum), and a one-shot check scheduled for just after the
 * earliest known finish time, so a completion is reported within seconds to
 * a minute instead of up to 15 minutes late. Android may still defer
 * background work, especially in deep sleep.
 *
 * Tracked queue state is persisted to plain SharedPreferences between runs
 * since a fresh process may back each invocation; nothing stored there is
 * sensitive (village names and building ids only, no session or credentials).
 */
public class NotifierWorker extends Worker {

    private static final String TAG = "TravianNotifier";
    private static final String CHANNEL_ID = NotifierBootstrap.CHANNEL_ID;
    private static final String STATE_PREFS = "travian_notifier_state";
    private static final String STATE_KEY = "tracked_events";
    private static final long SESSION_SEED_TTL_MS = TimeUnit.DAYS.toMillis(3650);

    private static final String NEXT_WORK_NAME = "travian-notifier-next";
    private static final String KEY_RETRIES = "retries";
    /** Wait a few seconds past the finish time so the server has processed the completion. */
    private static final long SETTLE_BUFFER_MS = 3_000L;
    /** A finish time that passed this recently but is still listed means the server is lagging: recheck. */
    private static final long LAG_WINDOW_MS = 60_000L;
    private static final long LAG_RETRY_MS = 20_000L;
    private static final int MAX_LAG_RETRIES = 5;
    private static final long MAX_SCHEDULE_AHEAD_MS = TimeUnit.DAYS.toMillis(2);
    /** An event that disappears earlier than this before its finish time was cancelled or sped up. */
    private static final long EARLY_TOLERANCE_MS = 30_000L;

    // earliest upcoming finish seen during this run (epoch ms), and whether a just-passed one is still listed
    private long nextWakeMs = Long.MAX_VALUE;
    private boolean lagging = false;

    public NotifierWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.i(TAG, "check started");
            String sessionCookie = TravianSession.readLobbySessionCookie(getApplicationContext());
            if (sessionCookie == null) {
                Log.i(TAG, "no game session found (not logged into the game yet), skipping");
                return Result.success();
            }

            SimpleCookieJar jar = new SimpleCookieJar();
            OkHttpClient http = TravianApi.newClient(jar);
            String gameworldHost = resumeSession(http, jar, sessionCookie);
            if (gameworldHost == null) {
                return Result.success();
            }

            poll(http, gameworldHost);
            scheduleNextCheck();
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "notifier check failed, will retry: " + e);
            return Result.retry();
        }
    }

    // ------------------------------------------------------------------
    // scheduling the next precise check
    // ------------------------------------------------------------------

    private void scheduleNextCheck() {
        long now = System.currentTimeMillis();
        int retries = getInputData().getInt(KEY_RETRIES, 0);
        long delayMs;
        int nextRetries = 0;
        if (lagging && retries < MAX_LAG_RETRIES) {
            delayMs = LAG_RETRY_MS;
            nextRetries = retries + 1;
        } else if (nextWakeMs != Long.MAX_VALUE && nextWakeMs - now <= MAX_SCHEDULE_AHEAD_MS) {
            delayMs = Math.max(nextWakeMs - now, 0L) + SETTLE_BUFFER_MS;
        } else {
            return; // nothing upcoming (or nothing trustworthy): the periodic check covers it
        }

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NotifierWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(new Data.Builder().putInt(KEY_RETRIES, nextRetries).build())
                .build();
        WorkManager.getInstance(getApplicationContext())
                .enqueueUniqueWork(NEXT_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
        Log.i(TAG, "next check scheduled in " + (delayMs / 1000) + "s");
    }

    /** Records a finish time from the server: schedules around it, or flags server lag if it just passed. */
    private void noteFinish(String label, long finishMs) {
        if (finishMs <= 0) {
            return;
        }
        long delta = finishMs - System.currentTimeMillis();
        Log.i(TAG, label + " finishes in " + (delta / 1000) + "s");
        if (delta > 0) {
            nextWakeMs = Math.min(nextWakeMs, finishMs);
        } else if (-delta <= LAG_WINDOW_MS) {
            lagging = true;
        }
    }

    /** The API's timestamps are epoch seconds; tolerate milliseconds too. */
    private static long toMillis(long ts) {
        if (ts <= 0) {
            return 0;
        }
        return ts > 100_000_000_000L ? ts : ts * 1000L;
    }

    // ------------------------------------------------------------------
    // session resume (reuses the game's own cookie, see TravianSession)
    // ------------------------------------------------------------------

    private String resumeSession(OkHttpClient http, SimpleCookieJar jar, String sessionCookie) throws Exception {
        String lobbyHost = TravianApi.hostOf(TravianApi.LOBBY_HOST);
        Cookie cookie = new Cookie.Builder()
                .name(TravianApi.LOBBY_SESSION_COOKIE)
                .value(sessionCookie)
                .domain(lobbyHost)
                .path("/")
                .httpOnly()
                .secure()
                .expiresAt(System.currentTimeMillis() + SESSION_SEED_TTL_MS)
                .build();
        jar.seed(lobbyHost, cookie);

        String avatarsQuery = "{ \"query\": \"query { a: avatars(wuid: null, context: null) "
                + "{ uuid, gameworld { metadata { url } } } }\" }";
        Request avatarsReq = new Request.Builder()
                .url(TravianApi.LOBBY_HOST + "/api/graphql")
                .post(TravianApi.jsonBody(avatarsQuery))
                .build();
        JSONObject avatarsResp = TravianApi.executeJson(http, avatarsReq);
        JSONObject data = avatarsResp.optJSONObject("data");
        if (data == null) {
            Log.w(TAG, "game's session was rejected by lobby: " + avatarsResp);
            return null;
        }
        JSONArray avatars = data.getJSONArray("a");
        if (avatars.length() == 0) {
            return null;
        }
        JSONObject avatar = avatars.getJSONObject(0);
        String avatarUuid = avatar.getString("uuid");
        String worldUrl = avatar.getJSONObject("gameworld").getJSONObject("metadata").getString("url");
        String worldHost = worldUrl.endsWith("/") ? worldUrl.substring(0, worldUrl.length() - 1) : worldUrl;

        Request playReq = new Request.Builder()
                .url(TravianApi.LOBBY_HOST + "/api/avatar/play/" + avatarUuid)
                .post(TravianApi.emptyBody())
                .build();
        JSONObject playResp = TravianApi.executeJson(http, playReq);
        String worldCode = playResp.getString("code");

        Request worldAuthReq = new Request.Builder()
                .url(worldHost + "/api/v1/auth?redirect=false&code=" + worldCode + "&response_type=token")
                .post(TravianApi.emptyBody())
                .build();
        TravianApi.executeJson(http, worldAuthReq); // sets JWT cookie for worldHost

        return worldHost;
    }

    // ------------------------------------------------------------------
    // polling
    // ------------------------------------------------------------------

    private static final String POLL_QUERY =
            "{ \"query\": \"query { p: ownPlayer { villages { id name x y "
            + "buildEvents { id buildingTypeId aspiredLevel timestamp status isActive } "
            + "trainingTroops { eventId unitsLeft nextUnitReadyAt lastUnitReadyAt } "
            + "stable { trainingUnits { eventId unitsLeft nextUnitReadyAt lastUnitReadyAt } } "
            + "barracks { trainingUnits { eventId unitsLeft nextUnitReadyAt lastUnitReadyAt } } "
            + "} } }\" }";

    private void poll(OkHttpClient http, String gameworldHost) throws Exception {
        Request req = new Request.Builder()
                .url(gameworldHost + "/api/v1/graphql")
                .post(TravianApi.jsonBody(POLL_QUERY))
                .build();
        JSONObject resp = TravianApi.executeJson(http, req);
        JSONObject data = resp.optJSONObject("data");
        if (data == null) {
            throw new IllegalStateException("no data in poll response: " + resp);
        }
        JSONObject player = data.getJSONObject("p");
        JSONArray villages = player.getJSONArray("villages");

        Map<String, TrackedEvent> tracked = loadTrackedState();
        Map<String, TrackedEvent> stillActive = new HashMap<String, TrackedEvent>();

        for (int i = 0; i < villages.length(); i++) {
            JSONObject village = villages.getJSONObject(i);
            String villageName = village.optString("name", "your village");
            int vx = village.optInt("x", 0);
            int vy = village.optInt("y", 0);

            JSONArray buildEvents = village.optJSONArray("buildEvents");
            if (buildEvents != null) {
                for (int j = 0; j < buildEvents.length(); j++) {
                    JSONObject ev = buildEvents.getJSONObject(j);
                    String id = "build:" + ev.optLong("id");
                    boolean active = ev.optBoolean("isActive", false);
                    long finishMs = toMillis(ev.optLong("timestamp", 0));
                    stillActive.put(id, new TrackedEvent("build", villageName, vx, vy,
                            ev.optInt("buildingTypeId", -1), ev.optInt("aspiredLevel", -1), 0, finishMs));
                    Log.i(TAG, "build event " + id + " active=" + active + " raw timestamp=" + ev.optLong("timestamp", 0));
                    if (active) {
                        noteFinish("build " + id, finishMs);
                    }
                }
            }
            collectQueue(village.optJSONArray("trainingTroops"), "train", villageName, vx, vy, tracked, stillActive);
            JSONObject stable = village.optJSONObject("stable");
            if (stable != null) {
                collectQueue(stable.optJSONArray("trainingUnits"), "stable", villageName, vx, vy, tracked, stillActive);
            }
            JSONObject barracks = village.optJSONObject("barracks");
            if (barracks != null) {
                collectQueue(barracks.optJSONArray("trainingUnits"), "barracks", villageName, vx, vy, tracked, stillActive);
            }
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, TrackedEvent> entry : tracked.entrySet()) {
            if (stillActive.containsKey(entry.getKey())) {
                continue;
            }
            TrackedEvent gone = entry.getValue();
            if (gone.finishMs > 0 && now < gone.finishMs - EARLY_TOLERANCE_MS) {
                Log.i(TAG, "event " + entry.getKey() + " vanished before its finish time (cancelled or sped up), not notifying");
                continue;
            }
            notify(describeCompletion(gone));
        }
        saveTrackedState(stillActive);

        Log.i(TAG, "poll ok: villages=" + villages.length() + " active=" + stillActive.size());
    }

    private void collectQueue(JSONArray queue, String kind, String villageName, int vx, int vy,
                               Map<String, TrackedEvent> tracked, Map<String, TrackedEvent> stillActive) {
        if (queue == null) {
            return;
        }
        for (int j = 0; j < queue.length(); j++) {
            JSONObject ev = queue.optJSONObject(j);
            if (ev == null) {
                continue;
            }
            String id = kind + ":" + ev.optLong("eventId") + ":" + villageName;
            // keep the count as first observed -- unitsLeft counts down each poll
            TrackedEvent existing = tracked.get(id);
            int initialUnits = existing != null ? existing.initialUnitsLeft : ev.optInt("unitsLeft", 0);
            long finishMs = toMillis(ev.optLong("lastUnitReadyAt", 0));
            stillActive.put(id, new TrackedEvent(kind, villageName, vx, vy, -1, -1, initialUnits, finishMs));
            Log.i(TAG, kind + " event " + id + " raw lastUnitReadyAt=" + ev.optLong("lastUnitReadyAt", 0));
            noteFinish(kind + " " + id, finishMs);
        }
    }

    private String describeCompletion(TrackedEvent ev) {
        String location = ev.villageName + " (" + ev.villageX + "|" + ev.villageY + ")";
        if ("build".equals(ev.kind)) {
            String name = GameData.buildingName(ev.buildingTypeId);
            String level = ev.aspiredLevel >= 0 ? " upgraded to level " + ev.aspiredLevel : " upgrade finished";
            return name + level + " — " + location;
        }
        String label = "train".equals(ev.kind) ? "Troop training"
                : "stable".equals(ev.kind) ? "Stable training"
                : "Barracks training";
        String count = ev.initialUnitsLeft > 0 ? " (" + ev.initialUnitsLeft + " units)" : "";
        return label + " finished" + count + " — " + location;
    }

    // ------------------------------------------------------------------
    // tracked-event state, persisted across worker runs -- not sensitive,
    // just village names/building ids, no session or credentials involved
    // ------------------------------------------------------------------

    private static final class TrackedEvent {
        final String kind; // "build" | "train" | "stable" | "barracks"
        final String villageName;
        final int villageX;
        final int villageY;
        final int buildingTypeId; // -1 for troop-training events
        final int aspiredLevel; // -1 for troop-training events
        final int initialUnitsLeft; // 0 for build events
        final long finishMs; // server-reported finish time (epoch ms), 0 if unknown

        TrackedEvent(String kind, String villageName, int villageX, int villageY,
                     int buildingTypeId, int aspiredLevel, int initialUnitsLeft, long finishMs) {
            this.kind = kind;
            this.villageName = villageName;
            this.villageX = villageX;
            this.villageY = villageY;
            this.buildingTypeId = buildingTypeId;
            this.aspiredLevel = aspiredLevel;
            this.initialUnitsLeft = initialUnitsLeft;
            this.finishMs = finishMs;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("villageName", villageName);
            o.put("villageX", villageX);
            o.put("villageY", villageY);
            o.put("buildingTypeId", buildingTypeId);
            o.put("aspiredLevel", aspiredLevel);
            o.put("initialUnitsLeft", initialUnitsLeft);
            o.put("finishMs", finishMs);
            return o;
        }

        static TrackedEvent fromJson(JSONObject o) throws Exception {
            return new TrackedEvent(
                    o.getString("kind"), o.getString("villageName"),
                    o.getInt("villageX"), o.getInt("villageY"),
                    o.getInt("buildingTypeId"), o.getInt("aspiredLevel"), o.getInt("initialUnitsLeft"),
                    o.optLong("finishMs", 0));
        }
    }

    private Map<String, TrackedEvent> loadTrackedState() {
        Map<String, TrackedEvent> result = new HashMap<String, TrackedEvent>();
        try {
            String json = statePrefs().getString(STATE_KEY, null);
            if (json == null) {
                return result;
            }
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                result.put(id, TrackedEvent.fromJson(root.getJSONObject(id)));
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to load tracked state, starting fresh: " + e);
        }
        return result;
    }

    private void saveTrackedState(Map<String, TrackedEvent> state) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, TrackedEvent> entry : state.entrySet()) {
                root.put(entry.getKey(), entry.getValue().toJson());
            }
            statePrefs().edit().putString(STATE_KEY, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "failed to save tracked state: " + e);
        }
    }

    private SharedPreferences statePrefs() {
        return getApplicationContext().getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // notification -- one-shot and dismissible, no ongoing/foreground notice
    // ------------------------------------------------------------------

    private void notify(String text) {
        Context ctx = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int granted = ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS");
            if (granted != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping notification: " + text);
                return;
            }
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Travian: Legends")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        nm.notify((int) System.currentTimeMillis(), builder.build());
        Log.i(TAG, "notified: " + text);
    }
}
