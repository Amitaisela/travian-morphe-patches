package com.travianpatch.notifier;

import java.util.List;

/**
 * Adds up what a village's saved build order will cost, using only the exact per-level costs the game's
 * own rules table lists. An item the table has no cost for (unknown building type, or a level the table
 * doesn't list) is counted as unknown, never as free. Build time is not estimated here: the game gives no
 * per-level time, so no time is shown until one can be worked out from the game's own numbers. Pure logic
 * (no Android APIs) so it can be checked off-device.
 */
final class QueueEstimate {

    private QueueEstimate() {
    }

    static final class Result {
        final BuildQueueAutomation.Resources totalCost;
        /** How many order items have no cost in the game's table (left out of totalCost). */
        final int unknownCount;

        Result(BuildQueueAutomation.Resources totalCost, int unknownCount) {
            this.totalCost = totalCost;
            this.unknownCount = unknownCount;
        }
    }

    /** rules may be null (not downloaded yet): then every item is unknown. */
    static Result totalCost(BuildingRules rules, List<BuildOrderStore.Entry> order) {
        long lumber = 0, clay = 0, iron = 0, crop = 0;
        int unknown = 0;
        for (BuildOrderStore.Entry entry : order) {
            BuildingRules.Rule rule = rules == null ? null : rules.find(entry.buildingTypeId);
            BuildingRules.Level level = rule == null ? null : rule.levelData(entry.targetLevel);
            if (level == null) {
                unknown++;
                continue;
            }
            lumber += level.lumber;
            clay += level.clay;
            iron += level.iron;
            crop += level.crop;
        }
        return new Result(new BuildQueueAutomation.Resources(lumber, clay, iron, crop), unknown);
    }
}
