package com.travianpatch.notifier;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Small helpers shared by the Travian Tools screens. Everything is built in code, so the game's APK
 * needs no layout resources from us.
 */
final class UiKit {

    static final int TEXT = Color.BLACK;
    static final int MUTED = 0xFF555555;

    private UiKit() {
    }

    static int dp(Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context ctx, String value, float sp, boolean bold, int color) {
        TextView view = new TextView(ctx);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    static TextView section(Context ctx, String title) {
        TextView view = text(ctx, title, 18, true, TEXT);
        view.setPadding(0, dp(ctx, 20), 0, dp(ctx, 4));
        return view;
    }

    static Button button(Context ctx, String label, View.OnClickListener onClick) {
        Button button = new Button(ctx);
        button.setText(label);
        button.setOnClickListener(onClick);
        return button;
    }

    static LinearLayout column(Context ctx) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        column.setPadding(pad, pad, pad, pad);
        return column;
    }

    /**
     * Wraps a column in a white scrolling page. The game targets a recent Android where windows draw
     * edge to edge, so the column is kept clear of the status and navigation bars.
     */
    static ScrollView page(final Context ctx, final LinearLayout column) {
        final int pad = dp(ctx, 16);
        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        scroll.addView(column);
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
}
