package com.travianpatch.notifier;

import java.util.ArrayList;
import java.util.List;

/**
 * One-off, read-only look at game data the next features need (round 3: celebrations, hero oasis finder,
 * troop escape, resource balancing). Every field name comes from the game's own client (its GraphQL
 * types); this logs what the server really answers so the features are built on live shapes. Only
 * "query" requests: nothing is changed in the game. Pure logic (no Android APIs) so it can be checked
 * off-device.
 */
final class DataProbe {

    /** Set when the probe starts, so it never runs a second time. */
    static final String KEY_DONE = "data_probe_done_v3";
    /** Longest slice of one response that is logged. */
    static final int MAX_LOGGED_CHARS = 16000;
    /** Android cuts a log line near 4 KB, so long text is logged in pieces of this size. */
    static final int LOG_PIECE = 3000;
    /** Map cells read around the village for oases: (2 * radius + 1)^2 cells. */
    static final int OASIS_RADIUS = 3;

    private DataProbe() {
    }

    static boolean shouldRun(boolean done, int knownVillages) {
        return !done && knownVillages > 0;
    }

    private static final String RESOURCES = "{ lumber clay iron crop }";
    private static final String UNITS = "{ t1 t2 t3 t4 t5 t6 t7 t8 t9 t10 t11 }";

    static List<String> queries(String villageId, int x, int y) {
        List<String> q = new ArrayList<String>();
        // Small unit table (the round-2 one was cut by the phone's log): animal (nature) stats for the oasis finder.
        q.add("query { bootstrapData { tribes { id units { id attackPower defencePowerAgainstInfantry "
                + "defencePowerAgainstCavalry velocity carry } } } }");
        String village = "query { ownVillage(id: " + villageId + ") { ";
        q.add(village + "hasRallyPoint townHall { celebrations { type cp duration canBeStarted celebrationCost "
                + RESOURCES + " } ongoingCelebrations { type finishedAt } smallCelebrationMaxCP greatCelebrationMaxCP "
                + "lastCelebrationTimestamp } } }");
        q.add(village + "heroMansion { withinReachOases { id x y type } annexedOases { id } } } }");
        q.add(village + "marketplace { merchantsInfo { total capacity offering underway available capacityAvailable "
                + "capacityTotal } } } }");
        q.add(village + "troops { ownTroopsAtTown { units " + UNITS + " } } troopOverview { "
                + "incomingAttacksRaidsPower { attack defence amount } ownTroopsPower { attack defence amount } } } }");
        q.add("query { ownPlayer { hero { health speed isAlive status { status arrivalAt } "
                + "attributes { code name value } } } }");
        q.add(oasisGrid(x, y, true));
        q.add(oasisGrid(x, y, false));
        // mapBlock failed ("Unexpected error") with a 7x7 box around the village; try a 10-aligned box once.
        int bx = Math.floorDiv(x, 10) * 10, by = Math.floorDiv(y, 10) * 10;
        q.add("query { mapBlock(xMin: " + bx + ", yMin: " + by + ", xMax: " + (bx + 9) + ", yMax: " + (by + 9)
                + ") { xMin yMin xMax yMax oases { id x y type } } }");
        q.add("query { ownPlayer { farmLists { id name ownerVillage { id } defaultTroop " + UNITS + " slotsAmount "
                + "runningRaidsAmount isExpanded sortIndex lastStartedTime useShip onlyLosses } } }");
        return q;
    }

    /**
     * Every map cell within OASIS_RADIUS of the village in one query (aliases c0, c1, ...), with the oasis on
     * it. withAnimals adds the animals (FreeOasis.troops); asked both ways in case the fragment is refused.
     */
    static String oasisGrid(int x, int y, boolean withAnimals) {
        String oasis = withAnimals
                ? "oasis { id x y type ... on FreeOasis { troops " + UNITS + " } }"
                : "oasis { id x y type }";
        StringBuilder b = new StringBuilder("query {");
        int n = 0;
        for (int dy = -OASIS_RADIUS; dy <= OASIS_RADIUS; dy++) {
            for (int dx = -OASIS_RADIUS; dx <= OASIS_RADIUS; dx++) {
                b.append(" c").append(n++).append(": mapCell(coordinates: { x: ").append(x + dx)
                        .append(", y: ").append(y + dy).append(" }) { x y type ").append(oasis).append(" }");
            }
        }
        return b.append(" }").toString();
    }

    /** The slots of one farm list (asked only when the account has a list). */
    static String farmSlotsQuery(long listId) {
        return "query { farmList(id: " + listId + ") { id slots { id target { id x y name } troop " + UNITS
                + " distance isActive isRunning runningAttacks nextAttackAt isSpying "
                + "lastRaid { time bootyMax icon raidedResources " + RESOURCES + " } } } }";
    }

    /** Splits text into pieces of at most `piece` characters (Android cuts long log lines). */
    static List<String> split(String text, int piece) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        for (int i = 0; i < text.length(); i += piece) {
            out.add(text.substring(i, Math.min(text.length(), i + piece)));
        }
        return out;
    }
}
