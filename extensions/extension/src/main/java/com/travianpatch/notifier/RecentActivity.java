package com.travianpatch.notifier;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** The Recent notifications screen: the latest notifications, newest first, muted ones marked. */
public class RecentActivity extends Activity {

    private static final long REFRESH_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView historyView;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            SharedPreferences state = getSharedPreferences(NotifierWorker.STATE_PREFS, Context.MODE_PRIVATE);
            historyView.setText(historyText(NotificationHistory.read(state.getString(NotifierWorker.KEY_HISTORY, null))));
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        column.addView(UiKit.text(this, "Recent notifications", 26, true, UiKit.TEXT));
        column.addView(UiKit.text(this, "Newest first. \"(muted)\" means the switch for that type was off, "
                + "so nothing was shown.", 14, false, UiKit.MUTED));
        historyView = UiKit.text(this, "", 14, false, UiKit.TEXT);
        historyView.setPadding(0, UiKit.dp(this, 12), 0, 0);
        column.addView(historyView);
        return UiKit.page(this, column);
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
}
