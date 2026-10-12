package io.github.flipcover.controls;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared rotation content for the full page and the control-center sheet. */
final class RotationControls extends LinearLayout {
    static final String RELEASE_LABEL = "交回系统旋转";
    private final CoverService owner;
    private final Runnable dismiss;
    private final GlassToggle automatic, reversed;
    private final TextView status;
    private final ControlRotationTabs angles;

    RotationControls(CoverService owner, Runnable dismiss) {
        super(PanelUi.controlContext(owner.screenContext)); this.owner = owner; this.dismiss = dismiss;
        Context context = getContext(); setOrientation(VERTICAL); setTag("rotation-controls"); setClipChildren(false); setClipToPadding(false);
        Runnable toggleAutomatic = () -> { owner.toggleRotation(); refresh(); };
        LinearLayout automaticRow = PanelUi.row(context, R.drawable.ic_ms_tune, "外屏自动旋转", "", toggleAutomatic);
        LinearLayout words = (LinearLayout) automaticRow.getChildAt(1);
        status = Ui.text(context, "", PanelUi.SECONDARY, Ui.MUTED); status.setTag("rotation-status"); status.setMinLines(2); status.setMaxLines(2); status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setPadding(0, Ui.dp(context, 2), 0, 0); words.addView(status);
        automatic = new GlassToggle(context, "外屏自动旋转", owner.rotationAutomatic(), toggleAutomatic); automatic.setTag("detail-switch-rotation"); automaticRow.addView(automatic); addView(automaticRow);
        angles = new ControlRotationTabs(context, owner.display.getRotation(), angle -> { owner.lockRotation(angle); dismiss.run(); });
        LayoutParams size = new LayoutParams(-2, Ui.dp(context, PanelUi.SLOT)); size.gravity = Gravity.CENTER_HORIZONTAL; size.setMargins(Ui.dp(context, PanelUi.INSET), Ui.dp(context, 3), Ui.dp(context, PanelUi.INSET), Ui.dp(context, 3)); addView(angles, size);
        Runnable toggleReversed = () -> { owner.prefs.reverseLandscapeRotation(!owner.prefs.reverseLandscapeRotation()); refresh(); };
        LinearLayout reverseRow = PanelUi.row(context, 0, "左右反向", "仅横屏快捷栏换边 · 白条位置不变", toggleReversed);
        reversed = new GlassToggle(context, "左右反向", owner.prefs.reverseLandscapeRotation(), toggleReversed); reversed.setTag("rotation-reverse-landscape"); reverseRow.addView(reversed); addView(reverseRow);
        TextView note = Ui.text(context, "更改屏幕方向可能会让液态玻璃临时失效,届时请手动重启带有液态玻璃的页面", PanelUi.SECONDARY, Ui.MUTED);
        note.setPadding(0, Ui.dp(context, PanelUi.GAP), 0, Ui.dp(context, PanelUi.GAP)); addView(note); refresh();
    }
    void refresh() {
        boolean enabled = owner.rotationAutomatic(); automatic.state(enabled); status.setText(enabled ? "已开启 · 所选外屏 (2秒轮询)" : "已关闭 · 所选外屏");
        reversed.state(owner.prefs.reverseLandscapeRotation()); angles.source(owner.display.getRotation());
    }
    void release() { owner.releaseRotation(); dismiss.run(); }
}
