package com.crowja.damiupos.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

/**
 * Helper to render settings forms read-only. Business settings are managed on the
 * web dashboard and synced down, so the on-device screens only display them.
 */
public final class ReadOnlyForms {

    private ReadOnlyForms() {}

    /** Disable the given views (greys out switches/buttons, blocks edits). */
    public static void disable(View... views) {
        for (View v : views) {
            if (v == null) continue;
            v.setEnabled(false);
            if (v instanceof EditText) {
                EditText e = (EditText) v;
                e.setFocusable(false);
                e.setFocusableInTouchMode(false);
                e.setCursorVisible(false);
                e.setLongClickable(false);
            }
        }
    }

    /** Recursively disable every editable/clickable control under a container. */
    public static void disableTree(View root) {
        if (root == null) return;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) disableTree(g.getChildAt(i));
        } else {
            disable(root);
        }
    }
}
