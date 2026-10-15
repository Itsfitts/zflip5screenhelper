package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Display;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Same-display inset reconciliation with a live search editor; emulator only. */
final class HubSearchChecks {
    private final Instrumentation test;
    private int assertions;
    HubSearchChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private Object field(Object object, String name) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void field(Object object, String name, Object value) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private Object call(CoverService owner, String name, Class<?>[] types, Object... args) { try { Method method = CoverService.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private boolean retains(CoverService owner, Display display, Point size, int rotation, float density) { return (boolean) call(owner, "retainHubSession", new Class<?>[]{String.class}, display.getDisplayId() + ":" + rotation + ":" + size + ":" + density + ":" + display.getCutout()); }
    String run() throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity); int oldDisplay = prefs.displayId(); boolean oldEnabled = prefs.enabled();
        ImageReader image = ImageReader.newInstance(720, 748, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay virtual = activity.getSystemService(DisplayManager.class).createVirtualDisplay("Search inset fixture", 720, 748, 340, image.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        require(virtual != null && virtual.getDisplay().getDisplayId() > 0, "isolated secondary display");
        CoverService previous = CoverService.instance; CoverService owner = new CoverService(); AppHubView[] hub = {null};
        try {
            prefs.data.edit().putInt("display", virtual.getDisplay().getDisplayId()).putBoolean("enabled", true).commit();
            CoverApp.catalog(activity).entriesBlocking();
            test.runOnMainSync(() -> {
                try { Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, activity); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                CoverService.instance = owner; owner.prefs = prefs; owner.display = virtual.getDisplay(); owner.screenContext = activity.createDisplayContext(virtual.getDisplay());
                field(owner, "displaySignature", virtual.getDisplay().getDisplayId() + ":" + virtual.getDisplay().getRotation() + ":" + SafeAreaManager.rawDisplaySize(virtual.getDisplay()) + ":" + owner.screenContext.getResources().getDisplayMetrics().density + ":" + virtual.getDisplay().getCutout());
                DockGeometry.Box frame = new DockGeometry.Box(0, 0, 720, 748);
                field(owner, "panelFrame", frame); field(owner, "hubFrame", frame); field(owner, "cardSafeFrame", frame); field(owner, "launcherFrameRotation", virtual.getDisplay().getRotation());
                field(owner, "placement", DockGeometry.resolve(720, 748, List.of(), 340f / 160, prefs.corner(0), prefs.widthRatio(), prefs.heightRatio(), true));
                hub[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
                activity.setContentView(owner.attachHubContent(hub[0])); field(owner, "hub", hub[0]);
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                EditText search = hub[0].findViewWithTag("hub-search"); View host = (View) hub[0].getParent();
                require(hub[0].isAttachedToWindow() && hub[0].expanded(), "mounted expanded launcher");
                require(search.requestFocus(), "search accepts focus"); search.setText("日常程序"); search.setSelection(1, 3);
                for (int i = 0; i < 4; i++) {
                    field(owner, "signature", "fixture IME navigation change " + i);
                    call(owner, "reconcile", new Class<?>[]{boolean.class}, false);
                    require(field(owner, "hub") == hub[0] && hub[0].getParent() == host && hub[0].isAttachedToWindow(), "inset change retains the same launcher and host");
                    require(hub[0].findViewWithTag("hub-search") == search && search.hasFocus() && search.getText().toString().equals("日常程序") && search.getSelectionStart() == 1 && search.getSelectionEnd() == 3, "inset change retains editor, text, focus and selection");
                    require((boolean) field(owner, "hubInsetsDeferred"), "inset update deferred while search session remains open");
                }
                Display display = virtual.getDisplay(); int rotation = display.getRotation(); float density = owner.screenContext.getResources().getDisplayMetrics().density;
                require(!retains(owner, display, new Point(721, 748), rotation, density), "real size change is not deferred");
                require(!retains(owner, display, new Point(720, 748), (rotation + 1) % 4, density), "real rotation is not deferred");
                require(!retains(owner, display, new Point(720, 748), rotation, density + .5f), "density change is not deferred");
                require(!retains(owner, activity.getDisplay(), new Point(720, 748), rotation, density), "another display is not retained");
                String mountedSignature = (String) field(owner, "displaySignature"); field(owner, "displaySignature", mountedSignature + ":old-cutout");
                require(!retains(owner, display, new Point(720, 748), rotation, density), "updated live display metadata cannot override the mounted cutout snapshot"); field(owner, "displaySignature", mountedSignature);
                search.clearFocus(); require(retains(owner, display, new Point(720, 748), rotation, density), "IME hide does not rebuild the expanded session after focus clears");
                hub[0].setExpanded(false); require(!retains(owner, display, new Point(720, 748), rotation, density), "collapsed Dock keeps ordinary inset behavior");
                call(owner, "resumeHubInsets", new Class<?>[0]); require(!(boolean) field(owner, "hubInsetsDeferred") && owner.main.hasCallbacks((Runnable) field(owner, "updateDisplay")), "collapse boundary can resume pending inset synchronization"); owner.main.removeCallbacksAndMessages(null); hub[0].setExpanded(true);
                field(owner, "hubInsetsDeferred", true);
                call(owner, "removeHubImmediately", new Class<?>[0]);
                require(field(owner, "hub") == null && !hub[0].isAttachedToWindow(), "closing releases the search session");
                require(!(boolean) field(owner, "hubInsetsDeferred") && owner.main.hasCallbacks((Runnable) field(owner, "updateDisplay")), "closing schedules deferred inset synchronization");
                owner.main.removeCallbacksAndMessages(null);
                require(!retains(owner, display, new Point(720, 748), rotation, density), "closed session cannot be retained or revived");
            });
            return "PASS: hub-search-insets; " + assertions + " assertions; real mounted editor with synthetic inset reconciliation, not Samsung IME routing";
        } finally {
            CoverService.instance = previous;
            test.runOnMainSync(() -> { owner.main.removeCallbacksAndMessages(null); if (hub[0] != null) hub[0].dispose(); activity.setContentView(new FrameLayout(activity)); activity.finish(); });
            prefs.data.edit().putInt("display", oldDisplay).putBoolean("enabled", oldEnabled).commit(); virtual.release(); image.close();
        }
    }
}
