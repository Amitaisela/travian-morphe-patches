package com.travianpatch.notifier;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The Notifications screen: one switch per notification type (built from NotificationKind, applied
 * at once), each with how many of that type happened in the last 24 hours, muted ones included.
 * Also where a notification's "Alert settings" button leads.
 */
public class NotificationSettingsActivity extends Activity {

    private static final long REFRESH_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<NotificationKind, TextView> countViews = new HashMap<NotificationKind, TextView>();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshCounts();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotifierBootstrap.ensureChannels(this);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private View buildContent() {
        LinearLayout column = UiKit.column(this);
        column.addView(UiKit.text(this, "Notifications", 26, true, UiKit.TEXT));
        column.addView(UiKit.text(this, "Choose which notifications you want. Changes apply right away. "
                + "A switched-off type is still counted below.", 14, false, UiKit.MUTED));
        for (NotificationKind kind : NotificationKind.values()) {
            column.addView(switchRow(kind));
        }
        return UiKit.page(this, column);
    }

    private View switchRow(final NotificationKind kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 4));

        Switch toggle = new Switch(this);
        toggle.setText(kind.label);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggle.setChecked(NotifierSettings.isEnabled(this, kind));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                NotifierSettings.setEnabled(NotificationSettingsActivity.this, kind, checked);
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(UiKit.text(this, kind.description, 13, false, UiKit.MUTED));
        TextView count = UiKit.text(this, "", 13, false, UiKit.MUTED);
        countViews.put(kind, count);
        row.addView(count);
        return row;
    }

    /** Updates the counts only (never the switches, so a tap in progress isn't fought). */
    private void refreshCounts() {
        long now = System.currentTimeMillis();
        SharedPreferences state = getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE);
        String history = state.getString(NotifierWorker.KEY_HISTORY, null);
        for (Map.Entry<NotificationKind, TextView> entry : countViews.entrySet()) {
            int count = NotificationHistory.countLast24h(history, entry.getKey().id, now);
            entry.getValue().setText(NotificationHistory.countLabel(count));
        }
    }
}
