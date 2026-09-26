package com.travianpatch.notifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Builds the game's own troop send request (/troop/send, a two-step send: see ActionClient.sendTwoStep).
 * Field names and numbers come from the game's code (RestAPI SendTroopsRequestBody / TroopsDataRequest,
 * RallyPoint.AttackType: Raid = 4). Pure logic (no Android APIs).
 */
final class TroopSend {

    static final String KIND = "TROOPS";
    static final String PATH = "/troop/send";
    /** The game's event type for a raid (RallyPoint.AttackType.Raid). */
    static final int RAID = 4;
    static final String[] UNITS = {"t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10", "t11"};
    /**
     * Stop after step 1 and show what the game answered. On until one watched try shows where the one-time
     * token comes back; then this is switched off in a later release.
     */
    static final boolean STEP_ONE_ONLY = true;

    private TroopSend() {
    }

    /** Raid the map cell (x|y) from this village with these units ("t1".."t11", hero = t11). */
    static GameAction raid(String villageId, int tribeId, int x, int y, Map<String, Integer> units, String label)
            throws Exception {
        int vid = Integer.parseInt(villageId);
        JSONObject troop = new JSONObject();
        int total = 0;
        for (String u : UNITS) {
            Integer n = units.get(u);
            int count = n == null ? 0 : Math.max(0, n);
            troop.put(u, count);
            total += count;
        }
        if (total == 0) {
            throw new IllegalArgumentException("no troops chosen");
        }
        troop.put("villageId", vid).put("tribeId", tribeId).put("useShip", false);
        JSONObject body = new JSONObject()
                .put("action", "troopsSend")
                .put("eventType", RAID)
                .put("villageId", vid)
                .put("target", new JSONObject().put("x", x).put("y", y))
                .put("redeployHero", false)
                .put("troops", new JSONArray().put(troop));
        return new GameAction(KIND, villageId, PATH, body, label, "troops:" + villageId + ":" + x + "|" + y);
    }
}
