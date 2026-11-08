package com.spx.chat;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Presentation for the A layout. Permissions and member actions stay in MainActivity. */
final class GroupUi {
    private GroupUi() {}

    static LinearLayout page(Context context, String title, Runnable back) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MallUi.BACKGROUND);
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        View arrow = MallUi.icon(context, "back", MallUi.PRIMARY);
        arrow.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        arrow.setContentDescription("返回上一页");
        arrow.setOnClickListener(v -> back.run());
        bar.addView(arrow, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        TextView heading = label(context, title, 17, MallUi.INK, true);
        heading.setGravity(Gravity.CENTER);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(new View(context), new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    static LinearLayout card(Context context, LinearLayout parent) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        card.setBackground(MallUi.surface(context, Color.WHITE, 18, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(context, 12));
        parent.addView(card, params);
        return card;
    }

    static TextView label(Context context, String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, 1);
        return view;
    }

    static View menu(Context context, LinearLayout parent, String icon, String title, String value, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 58));
        row.setPadding(0, dp(context, 9), 0, dp(context, 9));
        View picture = MallUi.icon(context, icon, MallUi.PRIMARY);
        picture.setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7));
        picture.setBackground(MallUi.surface(context, MallUi.TINT, 10, false));
        row.addView(picture, new LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)));
        LinearLayout labels = new LinearLayout(context);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(context, title, 14, MallUi.INK, false);
        name.setMaxLines(2);
        labels.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) {
            TextView summary = label(context, value, 11, MallUi.SECONDARY, false);
            summary.setMaxLines(1);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setGravity(Gravity.END);
            LinearLayout.LayoutParams meta = new LinearLayout.LayoutParams(0, -2, .85f);
            meta.setMargins(dp(context, 8), 0, 0, 0);
            labels.addView(summary, meta);
        }
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMargins(dp(context, 10), 0, dp(context, 6), 0);
        row.addView(labels, labelParams);
        if (action != null) row.addView(MallUi.icon(context, "chevron", MallUi.SECONDARY), new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)));
        if (action != null) { MallUi.touch(row, 12); row.setOnClickListener(v -> action.run()); }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    static void section(Context context, LinearLayout parent, String title) {
        View divider = new View(context);
        divider.setBackgroundColor(MallUi.BACKGROUND);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(context, 1));
        line.setMargins(0, dp(context, 8), 0, 0);
        parent.addView(divider, line);
        TextView heading = label(context, title, 12, MallUi.SECONDARY, true);
        heading.setPadding(0, dp(context, 14), 0, dp(context, 4));
        parent.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }

    static LinearLayout switchRow(Context context, String icon, String title, boolean checked) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 58));
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));
        LinearLayout content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER_VERTICAL);
        View picture = MallUi.icon(context, icon, MallUi.PRIMARY);
        picture.setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7));
        picture.setBackground(MallUi.surface(context, MallUi.TINT, 10, false));
        content.addView(picture, new LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)));
        TextView name = label(context, title, 14, MallUi.INK, false);
        name.setMaxLines(2);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, -2, 1);
        text.setMargins(dp(context, 10), 0, dp(context, 8), 0);
        content.addView(name, text);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(context, 44)));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return row;
    }

    static TextView roleBadge(Context context, String role, String title) {
        int foreground = "owner".equals(role) ? Color.rgb(155, 96, 24)
                : "admin".equals(role) ? MallUi.PRIMARY : MallUi.SECONDARY;
        int background = "owner".equals(role) ? Color.rgb(255, 244, 222)
                : "admin".equals(role) ? MallUi.TINT : MallUi.BACKGROUND;
        TextView badge = label(context, title, 10, foreground, true);
        badge.setSingleLine(true);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3));
        badge.setBackground(MallUi.surface(context, background, 5, false));
        return badge;
    }

    private static int dp(Context c, int v) { return MallUi.dp(c, v); }

    /** Measures against available width, including split-screen; never creates horizontal overflow. */
    static final class MemberGrid extends ViewGroup {
        private int columns = 5;
        private int cellHeight;
        MemberGrid(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            columns = Math.max(1, Math.min(5, available / dp(getContext(), 56)));
            cellHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(available / columns, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                cellHeight = Math.max(cellHeight, child.getMeasuredHeight());
            }
            int rows = (getChildCount() + columns - 1) / columns;
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(rows * cellHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = r - l - getPaddingLeft() - getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) {
                int column = i % columns;
                int left = getPaddingLeft() + column * available / columns;
                int right = getPaddingLeft() + (column + 1) * available / columns;
                int top = getPaddingTop() + i / columns * cellHeight;
                getChildAt(i).layout(left, top, right, top + cellHeight);
            }
        }
    }
}
