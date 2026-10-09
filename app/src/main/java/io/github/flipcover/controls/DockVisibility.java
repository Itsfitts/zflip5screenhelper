package io.github.flipcover.controls;

import java.util.Set;

/** Saved mode is supplied by Prefs; arrow expansion belongs to the current screen session. */
final class DockVisibility {
    private boolean expandable, expanded;
    private boolean clock, clockExpanded;
    private String foreground = "";
    private boolean keyboard;
    private Set<String> matchedApplications;
    private String matchedForeground;
    private boolean automaticCompact;
    void foreground(String packageName) {
        String next = packageName == null ? "" : packageName;
        if (next.equals(foreground)) return;
        foreground = next;
    }
    void keyboard(boolean visible) { keyboard = visible; }
    void expandable(boolean value) { if (expandable != value) { expandable = value; expanded = false; clockExpanded = false; } }
    boolean expandable() { return expandable; }
    void clock(boolean value) { if (clock != value) clockExpanded = false; clock = value; }
    void rulesChanged() { matchedApplications = null; matchedForeground = null; }
    /** Application rules are an immutable configuration snapshot, replaced on changes. */
    boolean compact(boolean automatic, Set<String> compactApps) {
        if (clock) return expandable ? !expanded : !clockExpanded;
        if (automaticallyCompact(automatic, compactApps)) return true;
        if (expandable) return !expanded;
        return keyboard;
    }
    boolean automaticallyCompact(boolean automatic, Set<String> compactApps) {
        if (clock || !automatic || compactApps.isEmpty()) return false;
        if (matchedApplications != compactApps || matchedForeground != foreground) {
            matchedApplications = compactApps; matchedForeground = foreground;
            automaticCompact = !foreground.isEmpty() && compactApps.contains(foreground);
        }
        return automaticCompact;
    }
    /** Samsung's unlocked clock is a System UI application window, distinct from SubLauncherWindow cards. */
    static boolean clockPage(String packageName, CharSequence title, CharSequence systemUiTitle, boolean active) {
        if (!active || title == null || "SubLauncherWindow".contentEquals(title)) return false;
        return "com.android.systemui".equals(packageName) || packageName == null && systemUiTitle != null && systemUiTitle.toString().contentEquals(title);
    }
    void toggle(boolean automatic, Set<String> compactApps) { if (clock && !expandable) clockExpanded = !clockExpanded; else if (expandable && !automaticallyCompact(automatic, compactApps)) expanded = !expanded; }
    void reset() { foreground = ""; keyboard = false; expanded = false; clock = false; clockExpanded = false; rulesChanged(); }
}
