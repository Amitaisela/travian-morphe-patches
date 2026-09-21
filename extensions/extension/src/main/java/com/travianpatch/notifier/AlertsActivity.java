package com.travianpatch.notifier;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.List;

/**
 * The "Travian Alerts" screen: a switch per notification type (built from NotificationKind, applied
 * at once), live status of the background checks, and the most recent notifications. Plain
 * programmatic views, so it needs no resources added to the game's APK.
 */
public class AlertsActivity extends Activity {

    private static final long REFRESH_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView notificationsView;
    private TextView lastCheckView;
    private TextView nextCheckView;
    private TextView watchingView;
    private TextView historyView;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshLive();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotifierBootstrap.ensureChannels(this); // this screen can be opened before the game ever was
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
        final int pad = dp(16);
        final LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        column.addView(text("Travian Alerts", 26, true, Color.BLACK));
        column.addView(text("Choose which notifications you want. Changes apply right away.", 14, false, 0xFF555555));

        column.addView(section("Status"));
        notificationsView = text("", 14, false, Color.BLACK);
        lastCheckView = text("", 14, false, Color.BLACK);
        nextCheckView = text("", 14, false, Color.BLACK);
        watchingView = text("", 14, false, Color.BLACK);
        column.addView(notificationsView);
        column.addView(lastCheckView);
        column.addView(nextCheckView);
        column.addView(watchingView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        Button checkNow = new Button(this);
        checkNow.setText("Check now");
        checkNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkNow();
            }
        });
        Button openGame = new Button(this);
        openGame.setText("Open Travian");
        openGame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGame();
            }
        });
        buttons.addView(checkNow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(openGame, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(buttons);

        column.addView(section("Notifications"));
        for (NotificationKind kind : NotificationKind.values()) {
            column.addView(switchRow(kind));
        }

        column.addView(section("Recent notifications"));
        historyView = text("", 14, false, Color.BLACK);
        column.addView(historyView);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        scroll.addView(column);
        // The game targets a recent Android where windows draw edge to edge; keep content clear of the bars.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                column.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                        pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        return scroll;
    }

    private View switchRow(final NotificationKind kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        Switch toggle = new Switch(this);
        toggle.setText(kind.label);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggle.setChecked(NotifierSettings.isEnabled(this, kind));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                NotifierSettings.setEnabled(AlertsActivity.this, kind, checked);
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(text(kind.description, 13, false, 0xFF555555));
        return row;
    }

    /** Updates the live parts only (never the switches, so a tap in progress isn't fought). */
    private void refreshLive() {
        long now = System.currentTimeMillis();
        SharedPreferences state = getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE);
        AlertStatus status = AlertStatus.fromJson(state.getString(NotifierWorker.KEY_STATUS, null));
        notificationsView.setText(notificationsLine());
        lastCheckView.setText(status.lastCheckLine(now));
        nextCheckView.setText(status.nextCheckLine(now));
        watchingView.setText(status.watchingLine());
        historyView.setText(historyText(NotificationHistory.read(state.getString(NotifierWorker.KEY_HISTORY, null))));
    }

    private String notificationsLine() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean allowed = nm != null && nm.areNotificationsEnabled();
        return allowed ? "Android notifications: allowed"
                : "Android notifications: BLOCKED for this app. Turn them on in Android settings or nothing will show.";
    }

    private static String historyText(List<NotificationHistory.Entry> entries) {
        if (entries.isEmpty()) {
            return "Nothing yet. Notifications you get will be listed here.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(NotificationHistory.line(entries.get(i)));
        }
        return sb.toString();
    }

    private void checkNow() {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NotifierWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(getApplicationContext())
                .enqueueUniqueWork(NotifierWorker.CHECK_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request);
        Toast.makeText(this, "Checking now…", Toast.LENGTH_SHORT).show();
    }

    private void openGame() {
        Intent intent = GameLauncher.launchIntent(this);
        if (intent != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "Couldn't find the Travian game to open", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView section(String title) {
        TextView view = text(title, 18, true, Color.BLACK);
        view.setPadding(0, dp(20), 0, dp(4));
        return view;
    }

    private TextView text(String value, float sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
