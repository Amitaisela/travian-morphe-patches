package com.travianpatch.notifier;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The Village tab: everything for one village on one screen - what is building now, the Auto-build switch
 * with its latest status, the queue, and what can be built (Build now or add to the queue). All numbers
 * come from the game's own data saved by the background check.
 */
final class VillageTab implements HubActivity.Tab {

    private static final String KEY_SELECTED = "selected_village";
    private static final String KEY_FILTER = "build_filter";

    private final HubActivity a;
    private final List<TextView> countdowns = new ArrayList<TextView>();
    private final List<QueueView.Item> countdownItems = new ArrayList<QueueView.Item>();

    private List<VillageList.Entry> villages;
    private PlayerBuildings player;
    private BuildingRules rules;
    private List<VillageResources.Entry> resources;
    private String villageId;
    private List<BuildOrderStore.Entry> order;

    VillageTab(HubActivity a) {
        this.a = a;
    }

    private SharedPreferences orders() {
        return a.getSharedPreferences(BuildOrderStore.PREFS, Context.MODE_PRIVATE);
    }

    private void load() {
        SharedPreferences state = a.getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE);
        villages = VillageList.fromJson(state.getString(NotifierWorker.KEY_VILLAGES, null));
        player = PlayerBuildings.parse(state.getString(NotifierWorker.KEY_PLAYER_BUILDINGS, null));
        rules = BuildingRules.parse(a.getSharedPreferences(BuildingRules.PREFS, Context.MODE_PRIVATE)
                .getString(BuildingRules.KEY_JSON, null));
        resources = VillageResources.fromJson(state.getString(NotifierWorker.KEY_VILLAGE_RESOURCES, null));
        villageId = orders().getString(KEY_SELECTED, null);
        boolean known = false;
        for (VillageList.Entry v : villages) {
            known |= v.id.equals(villageId);
        }
        if (!known) {
            villageId = villages.isEmpty() ? null : villages.get(0).id;
        }
        order = villageId == null ? new ArrayList<BuildOrderStore.Entry>()
                : BuildOrderStore.fromJson(orders().getString(BuildOrderStore.key(villageId), null));
    }

    @Override
    public View build() {
        load();
        countdowns.clear();
        countdownItems.clear();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        if (villageId == null) {
            col.addView(message("No village read yet. Open the game once and wait for the first check."));
            return col;
        }
        col.addView(villageHeader());
        ActionClient.Settings s = ActionSender.settings(a);
        boolean autoOn = orders().getBoolean(BuildOrderStore.autoKey(villageId), false);
        if (autoOn && s.dryRun) {
            col.addView(banner("Practice mode is on: auto-build only writes what it would do. Turn it off in Settings."));
        }
        col.addView(UiKit.section(a, "Building now"));
        col.addView(buildingNowCard(), UiKit.cardParams(a));
        col.addView(UiKit.section(a, "Auto-build"));
        col.addView(autoCard(autoOn), UiKit.cardParams(a));
        col.addView(UiKit.section(a, "Queue"));
        col.addView(queueCard(), UiKit.cardParams(a));
        col.addView(UiKit.section(a, "Build"));
        col.addView(buildSection());
        return col;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < countdowns.size(); i++) {
            countdowns.get(i).setText(QueueView.when(countdownItems.get(i), now));
        }
    }

    // ------------------------------------------------------------------ sections

    private View villageHeader() {
        if (villages.size() == 1) {
            TextView name = UiKit.text(a, villages.get(0).label(), 17, true, UiKit.textColor(a));
            name.setPadding(UiKit.dp(a, 4), UiKit.dp(a, 8), 0, 0);
            return name;
        }
        Spinner picker = new Spinner(a);
        List<String> labels = new ArrayList<String>();
        int selected = 0;
        for (int i = 0; i < villages.size(); i++) {
            labels.add(villages.get(i).label());
            if (villages.get(i).id.equals(villageId)) {
                selected = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(UiKit.textColor(a));
                v.setTextSize(17);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        picker.setSelection(selected, false);
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String picked = villages.get(position).id;
                if (!picked.equals(villageId)) {
                    orders().edit().putString(KEY_SELECTED, picked).apply();
                    a.redraw();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return picker;
    }

    private View buildingNowCard() {
        LinearLayout card = UiKit.card(a);
        String title = currentVillageTitle();
        String json = a.getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE)
                .getString(NotifierWorker.STATE_KEY, null);
        int shown = 0;
        for (QueueView.Group g : QueueView.parse(json)) {
            if (title != null && !title.equals(g.title)) {
                continue;
            }
            for (QueueView.Item item : g.items) {
                LinearLayout row = UiKit.listRow(a, item.label, null);
                TextView when = UiKit.text(a, QueueView.when(item, System.currentTimeMillis()), 14, false,
                        UiKit.accentText(a));
                row.addView(when);
                countdowns.add(when);
                countdownItems.add(item);
                if (shown > 0) {
                    card.addView(UiKit.divider(a));
                }
                card.addView(row);
                shown++;
            }
        }
        if (shown == 0) {
            card.addView(UiKit.muted(a, "Nothing is building or training."));
        }
        return card;
    }

    private View autoCard(boolean autoOn) {
        LinearLayout card = UiKit.card(a);
        Switch toggle = UiKit.accentSwitch(a, "Build the queue automatically", autoOn);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                orders().edit().putBoolean(BuildOrderStore.autoKey(villageId), checked).apply();
                if (checked) {
                    // One switch is enough: turning auto-build on also turns automatic actions on.
                    a.getSharedPreferences(ActionSender.PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean(ActionSender.KEY_MASTER, true).apply();
                }
                a.redraw();
            }
        });
        card.addView(toggle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        String notes = orders().getString(BuildOrderStore.notesKey(villageId), null);
        TextView status = UiKit.muted(a, autoOn ? (notes == null ? "Starts at the next check (within 5 minutes)." : notes)
                : "Off. The queue waits until you switch this on; ▶ still builds right away.");
        status.setPadding(0, UiKit.dp(a, 6), 0, 0);
        card.addView(status);
        return card;
    }

    private View queueCard() {
        LinearLayout card = UiKit.card(a);
        if (order.isEmpty()) {
            card.addView(UiKit.muted(a, "Empty. Tap + next to anything under Build to queue it."));
            return card;
        }
        QueueEstimate.Result total = QueueEstimate.totalCost(rules, village(), order);
        TextView sum = UiKit.muted(a, "Total " + Costs.shortLine(total.totalCost.lumber, total.totalCost.clay,
                total.totalCost.iron, total.totalCost.crop)
                + (total.unknownCount > 0 ? "  (+" + total.unknownCount + " not known)" : ""));
        sum.setPadding(0, 0, 0, UiKit.dp(a, 4));
        card.addView(sum);
        for (int i = 0; i < order.size(); i++) {
            final int index = i;
            BuildOrderStore.Entry e = order.get(i);
            String name = GameData.buildingName(e.buildingTypeId) + (e.slotId > 0 ? " · slot " + e.slotId : "");
            LinearLayout row = UiKit.listRow(a, (i + 1) + ".  " + name + "  → " + e.targetLevel, null);
            if (i > 0) {
                UiKit.addPill(row, UiKit.pill(a, "↑", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        move(index, -1);
                    }
                }));
            }
            UiKit.addPill(row, UiKit.pill(a, "✕", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    order.remove(index);
                    saveOrder();
                }
            }));
            card.addView(UiKit.divider(a));
            card.addView(row);
        }
        return card;
    }

    private View buildSection() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        final PlayerBuildings.Village village = village();
        if (rules == null || village == null) {
            col.addView(message("The game's building data hasn't been read yet. Open the game and wait a minute."));
            return col;
        }
        int filter = orders().getInt(KEY_FILTER, 0);
        col.addView(UiKit.segments(a, new String[]{"Buildings", "Fields", "New"}, filter, new UiKit.OnPick() {
            @Override
            public void picked(int index) {
                orders().edit().putInt(KEY_FILTER, index).apply();
                a.redraw();
            }
        }), UiKit.cardParams(a));
        List<BuildChoices.Row> rows = new ArrayList<BuildChoices.Row>();
        for (BuildChoices.Row r : BuildChoices.list(rules, player.tribeId, village)) {
            if (r.verdict.answer == BuildOptions.Answer.NO) {
                continue;
            }
            boolean field = r.typeId >= 1 && r.typeId <= 4;
            boolean isNew = r.slotId == 0;
            if ((filter == 0 && !field && !isNew) || (filter == 1 && field && !isNew) || (filter == 2 && isNew)) {
                rows.add(r);
            }
        }
        if (filter == 1) {
            Collections.sort(rows, new Comparator<BuildChoices.Row>() {
                @Override
                public int compare(BuildChoices.Row x, BuildChoices.Row y) {
                    return x.fromLevel != y.fromLevel ? x.fromLevel - y.fromLevel : x.slotId - y.slotId;
                }
            });
        }
        LinearLayout card = UiKit.card(a);
        if (rows.isEmpty()) {
            card.addView(UiKit.muted(a, "Nothing here can be built right now, going by the game's rules."));
        }
        VillageResources.Entry stock = VillageResources.find(resources, villageId);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                card.addView(UiKit.divider(a));
            }
            card.addView(choiceRow(rows.get(i), stock));
        }
        col.addView(card, UiKit.cardParams(a));
        return col;
    }

    private View choiceRow(final BuildChoices.Row r, VillageResources.Entry stock) {
        String name = GameData.buildingName(r.typeId);
        boolean field = r.typeId >= 1 && r.typeId <= 4;
        String title = r.slotId == 0 ? name + "  · new" : name + (field ? " · slot " + r.slotId : "")
                + "   " + r.fromLevel + " → " + r.toLevel;
        String sub;
        if (r.next == null) {
            sub = "cost not known";
        } else {
            sub = Costs.shortLine(r.next.lumber, r.next.clay, r.next.iron, r.next.crop);
            if (stock != null) {
                String miss = Costs.missing(stock.lumberStock, stock.clayStock, stock.ironStock, stock.cropStock,
                        r.next.lumber, r.next.clay, r.next.iron, r.next.crop, 0);
                if (miss.length() > 0) {
                    sub += "\n" + miss;
                }
            }
        }
        if (r.verdict.answer == BuildOptions.Answer.UNKNOWN) {
            sub += "\n" + r.verdict.reason;
        }
        LinearLayout row = UiKit.listRow(a, title, sub);
        if (r.verdict.answer == BuildOptions.Answer.YES) {
            UiKit.addPill(row, UiKit.pill(a, "▶", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmBuild(r);
                }
            }));
        }
        UiKit.addPill(row, UiKit.pill(a, "+", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addToQueue(r);
            }
        }));
        return row;
    }

    // ------------------------------------------------------------------ actions

    /** Adds the next level after anything already queued for the same slot (or the same new building). */
    private void addToQueue(BuildChoices.Row r) {
        int target = r.toLevel;
        for (BuildOrderStore.Entry e : order) {
            if (e.buildingTypeId == r.typeId && e.slotId == r.slotId && e.targetLevel >= target) {
                target = e.targetLevel + 1;
            }
        }
        BuildingRules.Rule rule = rules.find(r.typeId);
        if (rule != null && target > rule.maxLevel) {
            Toast.makeText(a, "Already queued up to the game's maximum (" + rule.maxLevel + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean replaced = false;
        for (int i = 0; i < order.size(); i++) {
            BuildOrderStore.Entry e = order.get(i);
            if (e.buildingTypeId == r.typeId && e.slotId == r.slotId) {
                order.set(i, new BuildOrderStore.Entry(r.typeId, target, r.slotId));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            order.add(new BuildOrderStore.Entry(r.typeId, target, r.slotId));
        }
        Toast.makeText(a, "Queued " + GameData.buildingName(r.typeId) + " → " + target, Toast.LENGTH_SHORT).show();
        saveOrder();
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= order.size()) {
            return;
        }
        Collections.swap(order, index, to);
        saveOrder();
    }

    private void saveOrder() {
        orders().edit().putString(BuildOrderStore.key(villageId), BuildOrderStore.toJson(order)).apply();
        a.redraw();
    }

    private void confirmBuild(final BuildChoices.Row r) {
        final String label = GameData.buildingName(r.typeId) + (r.slotId > 0 ? " (slot " + r.slotId + ")" : "")
                + " to " + r.toLevel;
        String cost = r.next == null ? "cost not known" : Costs.shortLine(r.next.lumber, r.next.clay, r.next.iron, r.next.crop);
        long nextAttack = a.getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE)
                .getLong(NotifierWorker.KEY_NEXT_ATTACK_AT, 0);
        long minutes = (nextAttack - System.currentTimeMillis()) / 60_000L;
        String warning = nextAttack > System.currentTimeMillis() && minutes < ActionSender.settings(a).attackPauseMinutes
                ? "\n\n⚠ An attack lands in about " + Math.max(0, minutes) + " min." : "";
        String practice = ActionSender.settings(a).dryRun ? "\n\nPractice mode is on: nothing will be sent." : "";
        new AlertDialog.Builder(a)
                .setTitle("Build now?")
                .setMessage(label + "\n" + cost + warning + practice)
                .setPositiveButton("Build", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        buildNow(r, label);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void buildNow(final BuildChoices.Row r, final String label) {
        final String vid = villageId;
        PlayerBuildings.Village village = village();
        final int slot = r.slotId > 0 ? r.slotId : (village == null ? 0 : BuildChoices.slotForNew(r.typeId, village));
        if (slot == 0) {
            Toast.makeText(a, "No free slot for it", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    ActionClient.Result res = ActionSender.sendFromScreen(a, GameActions.build(vid, slot, r.typeId, label));
                    text = label + ": " + res.describe();
                    if ("SENT".equals(res.outcome)) {
                        NotifierWorker.requestCheckNow(a);
                    }
                } catch (Exception e) {
                    text = label + ": failed (" + e.getClass().getSimpleName() + ")";
                }
                final String shown = text;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(a, shown, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ helpers

    private PlayerBuildings.Village village() {
        return player == null ? null : player.findVillage(villageId);
    }

    private String currentVillageTitle() {
        for (VillageList.Entry v : villages) {
            if (v.id.equals(villageId)) {
                return v.name + " (" + v.x + "|" + v.y + ")";
            }
        }
        return null;
    }

    private View message(String text) {
        LinearLayout card = UiKit.card(a);
        card.addView(UiKit.body(a, text));
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, UiKit.dp(a, 12), 0, 0);
        wrap.addView(card, UiKit.cardParams(a));
        return wrap;
    }

    private View banner(String text) {
        LinearLayout card = UiKit.card(a);
        card.setBackground(UiKit.rounded(UiKit.dark(a) ? 0xFF3A2A12 : 0xFFFFF1D6, 14, a));
        card.addView(UiKit.body(a, text));
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, UiKit.dp(a, 10), 0, 0);
        wrap.addView(card, UiKit.cardParams(a));
        return wrap;
    }
}
