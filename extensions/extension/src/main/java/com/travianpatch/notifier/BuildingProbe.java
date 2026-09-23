package com.travianpatch.notifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The exact read-only queries used, once per install, to learn the real shape of the game's building
 * data. Every name below comes from the game's own client (field names found in its compiled metadata);
 * none of them is confirmed against a live response yet, which is the whole point of running this.
 *
 * Two facts about this server shape the list (both seen in earlier phone logs): GraphQL introspection
 * is refused, and a nested field the schema doesn't have is silently dropped instead of raising an
 * error. So an object-typed field is also asked for "bare" (no sub-fields) to try to make the server
 * name the type in an error, and guessed sub-field names are tried in separate queries so one wrong
 * guess can't hide a right one. Pure logic (no Android APIs) so it can be checked off-device.
 */
final class BuildingProbe {

    private BuildingProbe() {
    }

    /** Run exactly once per install, and only after a poll has already seen at least one village. */
    static boolean shouldRun(boolean alreadyDone, int knownVillages) {
        return !alreadyDone && knownVillages > 0;
    }

    /** The queries for one village, or none if the id isn't 1-12 plain digits. */
    static List<String> queries(String villageId) {
        List<String> out = new ArrayList<String>();
        if (villageId == null || !villageId.matches("[0-9]{1,12}")) {
            return out;
        }

        // The game's own rules table (static per world/version).
        out.add(bootstrap("timestamp releaseVersion serverTitle serverTimezoneOffset"));
        out.add(bootstrap("buildingsRaw { maxLevel }"));
        out.add(bootstrap("buildings { maxLevel }"));
        out.add(bootstrap("buildingsRaw { id buildingTypeId typeId maxLevel maxPerVillage category sortIndex "
                + "baseBuildingTime buildingTimeFactor additionalBuildingTime validTribes validVillageTypes }"));
        out.add(bootstrap("buildingsRaw { levels }"));
        out.add(bootstrap("buildingsRaw { levels { level producedCulture producedPopulation effectValue cropUsage } }"));
        out.add(bootstrap("buildingsRaw { levels { buildCostObject } }"));
        out.add(bootstrap("buildingsRaw { levels { buildingCost } }"));
        out.add(bootstrap("buildingsRaw { levels { buildCostObject { lumber clay iron crop } } }"));
        out.add(bootstrap("buildingsRaw { levels { buildCostObject { wood clay iron crop } } }"));
        out.add(bootstrap("buildingsRaw { levels { buildCostObject { amounts } } }"));
        out.add(bootstrap("buildingsRaw { requiredBuildings }"));
        out.add(bootstrap("buildingsRaw { requiredBuildings { buildingTypeId level } }"));
        out.add(bootstrap("buildingsRaw { requiredBuildings { absoluteBuildingTypeId level } }"));
        out.add(bootstrap("buildingsRaw { restrictions }"));
        out.add(bootstrap("buildingsRaw { extension }"));
        out.add(bootstrap("serverConfiguration"));
        out.add(bootstrap("serverConfiguration { serverSpeed speed }"));
        out.add(bootstrap("serverSpeed speed"));
        out.add(bootstrap("gameworld"));

        // This village's own buildings and queue.
        out.add(village(villageId, "id name"));
        out.add("query { ownVillage(id: \"" + villageId + "\") { id name } }");
        out.add(village(villageId, "mainBuilding"));
        out.add(village(villageId, "villageLayoutType"));
        out.add(village(villageId, "buildings { slotId buildingTypeId level }"));
        out.add(village(villageId, "buildings { id slotId buildingTypeId level levelUpdateTimestamp rearrangedSlotId }"));
        out.add(village(villageId, "buildings { upgradeCostObject }"));
        out.add(village(villageId, "buildings { buildCostObject }"));
        out.add(village(villageId, "buildEvents { id buildingTypeId slotId aspiredLevel timestamp isActive }"));
        out.add("query { ownPlayer { villages { id buildings { slotId buildingTypeId level } } } }");
        return out;
    }

    private static String bootstrap(String selection) {
        return "query { bootstrapData { " + selection + " } }";
    }

    private static String village(String villageId, String selection) {
        return "query { ownVillage(id: " + villageId + ") { " + selection + " } }";
    }
}
