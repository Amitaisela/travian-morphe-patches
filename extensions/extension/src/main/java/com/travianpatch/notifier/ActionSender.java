package com.travianpatch.notifier;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The Android side of ActionClient: the real connection to the game world (the worker's session, or the
 * session the worker cached for screens), the user's switches, the dedupe memory and the action log.
 * Must be called off the main thread.
 */
final class ActionSender {

    private static final String TAG = "TravianNotifier";
    static final String PREFS = ActionLog.PREFS;
    static final String KEY_MASTER = "master_on";
    static final String KEY_DRY_RUN = "dry_run";
    static final String KEY_PAUSE_MIN = "attack_pause_minutes";
    private static final String KEY_RECENT = "recent_keys";

    private ActionSender() {
    }

    static ActionClient.Settings settings(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ActionClient.Settings(p.getBoolean(KEY_MASTER, ActionClient.DEFAULT_SETTINGS.masterOn),
                p.getBoolean(KEY_DRY_RUN, ActionClient.DEFAULT_SETTINGS.dryRun),
                p.getInt(KEY_PAUSE_MIN, ActionClient.DEFAULT_SETTINGS.attackPauseMinutes));
    }

    /** From a screen tap: uses the session the background check cached (about 2 hours). */
    static ActionClient.Result sendFromScreen(Context ctx, GameAction action) {
        SimpleCookieJar jar = new SimpleCookieJar();
        SharedPreferences state = ctx.getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE);
        String host = NotifierWorker.seedWorldToken(state, jar);
        if (host == null) {
            ActionClient.Result r = new ActionClient.Result("FAILED", 0,
                    "no game session yet: open the game, wait for the next check, then try again", true, null);
            record(ctx, action, r);
            return r;
        }
        return send(ctx, TravianApi.newClient(jar), host, action, false);
    }

    /** From the background worker, with its own signed-in client. */
    static ActionClient.Result send(Context ctx, final OkHttpClient http, final String host, GameAction action,
                                    boolean automated) {
        ActionClient.Transport transport = new ActionClient.Transport() {
            @Override
            public ActionClient.Response post(String path, String json) throws Exception {
                Request req = new Request.Builder()
                        .url(host + "/api/v1" + path)
                        .post(TravianApi.jsonBody(json))
                        .build();
                Response resp = http.newCall(req).execute();
                try {
                    return new ActionClient.Response(resp.code(), resp.body() == null ? "" : resp.body().string());
                } finally {
                    resp.close();
                }
            }
        };
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences state = ctx.getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE);
        Map<String, Long> recent = loadRecent(p.getString(KEY_RECENT, null));
        ActionClient.Settings settings = settings(ctx);
        long now = System.currentTimeMillis();
        long nextAttack = state.getLong(NotifierWorker.KEY_NEXT_ATTACK_AT, 0);

        if (("BUILD".equals(action.kind) || "TRAIN".equals(action.kind)) && knownVillages(state) > 1) {
            try {
                ActionClient.Result switched = ActionClient.sendWith(transport,
                        GameActions.changeVillage(action.villageId), automated, settings, now, nextAttack, recent);
                record(ctx, GameActionsLabel.of(action, "switch village"), switched);
                if (!"SENT".equals(switched.outcome) && !"DRY_RUN".equals(switched.outcome)) {
                    saveRecent(p, recent, now);
                    return switched;
                }
            } catch (Exception e) {
                Log.w(TAG, "village switch failed: " + e);
            }
        }
        ActionClient.Result result = ActionClient.sendWith(transport, action, automated, settings, now, nextAttack, recent);
        saveRecent(p, recent, now);
        record(ctx, action, result);
        Log.i(TAG, "action " + action.kind + " " + action.label + ": " + result.outcome
                + (result.httpCode > 0 ? " HTTP " + result.httpCode : "") + " " + ActionClient.serverMessage(
                result.responseBody == null ? "" : result.responseBody));
        return result;
    }

    private static int knownVillages(SharedPreferences state) {
        List<VillageList.Entry> v = VillageList.fromJson(state.getString(NotifierWorker.KEY_VILLAGES, null));
        return v.size();
    }

    static void record(Context ctx, GameAction action, ActionClient.Result r) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = ActionLog.add(p.getString(ActionLog.KEY, null), new ActionLog.Entry(System.currentTimeMillis(),
                action.kind, action.label, r.outcome, r.describe()), ActionLog.CAP);
        p.edit().putString(ActionLog.KEY, json).apply();
    }

    private static Map<String, Long> loadRecent(String json) {
        Map<String, Long> out = new HashMap<String, Long>();
        if (json == null) {
            return out;
        }
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, o.getLong(k));
            }
        } catch (Exception ignored) {
            // corrupt memory: start empty
        }
        return out;
    }

    private static void saveRecent(SharedPreferences p, Map<String, Long> recent, long now) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Long> e : recent.entrySet()) {
            if (now - e.getValue() < ActionGuard.DEDUPE_MS * 10) {
                try {
                    o.put(e.getKey(), e.getValue().longValue());
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        p.edit().putString(KEY_RECENT, o.toString()).apply();
    }

    /** Small helper so a village switch shows in the log next to the action it was for. */
    static final class GameActionsLabel {
        static GameAction of(GameAction a, String what) {
            return new GameAction("VILLAGE", a.villageId, "/village/change-current", a.body,
                    what + " for " + a.label, "log-only");
        }
    }
}
