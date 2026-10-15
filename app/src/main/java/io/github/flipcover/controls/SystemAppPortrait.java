package io.github.flipcover.controls;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IInterface;
import android.os.Parcel;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.SurfaceControl;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Owns one cover-only task display area. Android routes the app's native input and rendering. */
final class SystemAppPortrait {
    private final Context context;
    private final int user;
    private Session current;
    SystemAppPortrait(Context context, int ownerUid) { this.context = context; user = ownerUid / 100000; }

    synchronized void open(int displayId, String text) throws Exception {
        JSONObject request = new JSONObject(text);
        Display display = available(displayId, request);
        String name = request.getString("package"), id = request.getString("session");
        String ratio = request.optString("ratio", AppPortraitBounds.DEFAULT_RATIO);
        if (!AppPortraitBounds.RATIOS.contains(ratio)) throw new IllegalArgumentException("Invalid portrait ratio");
        AppDisplayPolicy.packageName(name);
        if (id.length() > 80) throw new IllegalArgumentException("Invalid session");
        ComponentName component = ComponentName.unflattenFromString(request.getString("component"));
        Intent canonical = context.getPackageManager().getLaunchIntentForPackage(name);
        if (component == null || canonical == null || !component.equals(canonical.getComponent()) || !name.equals(component.getPackageName())) throw new SecurityException("Not an application launcher");
        android.content.pm.ActivityInfo activity = context.getPackageManager().getActivityInfo(component, 0);
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled || activity.applicationInfo.uid / 100000 != user) throw new SecurityException("Unavailable application");
        close();
        Session session = new Session(displayId, SafeAreaManager.rotation(display), request, name, id);
        current = session;
        try {
            Class<?> organizerType = Class.forName("android.window.DisplayAreaOrganizer");
            session.organizer = organizerType.getConstructor(Executor.class).newInstance((Executor) Runnable::run);
            Object appeared = organizerType.getMethod("createTaskDisplayArea", int.class, int.class, String.class).invoke(session.organizer, displayId, organizerType.getField("FEATURE_ROOT").getInt(null), "FlipCoverPortrait");
            Object info = appeared.getClass().getMethod("getDisplayAreaInfo").invoke(appeared);
            session.token = info.getClass().getField("token").get(info);
            session.leash = (SurfaceControl) appeared.getClass().getMethod("getLeash").invoke(appeared);
            if (info.getClass().getField("displayId").getInt(info) != displayId) throw new SecurityException("Wrong display area");
            session.feature = info.getClass().getField("featureId").getInt(info);
            int width = SafeAreaManager.physicalSize(display).x, height = SafeAreaManager.physicalSize(display).y;
            if (session.rotation % 2 != 0) { int swap = width; width = height; height = swap; }
            DisplayCutout cutout = SafeAreaManager.cutout(display);
            session.bounds = AppPortraitBounds.fit(width, height, cutout == null ? 0 : cutout.getSafeInsetLeft(), cutout == null ? 0 : cutout.getSafeInsetTop(), cutout == null ? 0 : cutout.getSafeInsetRight(), cutout == null ? 0 : cutout.getSafeInsetBottom(), ratio);
            Rect bounds = new Rect(0, 0, session.bounds.width(), session.bounds.height());
            Class<?> transactionType = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionType.getConstructor().newInstance();
            transactionType.getMethod("setBounds", tokenType(), Rect.class).invoke(transaction, session.token, bounds);
            transactionType.getMethod("setAppBounds", tokenType(), Rect.class).invoke(transaction, session.token, bounds);
            Class<?> windowType = Class.forName("android.window.WindowOrganizer");
            windowType.getMethod("applyTransaction", transactionType).invoke(windowType.getConstructor().newInstance(), transaction);
            pose(session);
            session.manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            watch(session);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
            options.getClass().getMethod("setLaunchTaskDisplayArea", tokenType()).invoke(options, session.token);
            options.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(options, 1);
            available(displayId, request);
            int result = (int) managerType().getMethod("startActivityAsUser", Class.forName("android.app.IApplicationThread"), String.class, String.class, Intent.class, String.class, android.os.IBinder.class, String.class, int.class, int.class, Class.forName("android.app.ProfilerInfo"), android.os.Bundle.class, int.class)
                .invoke(session.manager, null, "com.android.shell", null, AppLauncher.applicationIntent(component), null, null, null, 0, 0, null, options.toBundle(), user);
            if (result < 0) throw new IllegalStateException("Launch rejected: " + result);
            pose(session);
            // One startup settle, not a foreground polling loop. Every later check comes from a system event.
            session.handler.postDelayed(() -> settled(session), 2500);
        } catch (Exception error) { close(); throw error; }
    }
    synchronized void close(String text) throws Exception {
        JSONObject request = new JSONObject(text);
        if (current != null && current.name.equals(request.optString("package")) && (!request.has("session") || current.id.equals(request.optString("session")))) close();
    }
    private Display available(int displayId, JSONObject request) {
        if (android.os.Build.VERSION.SDK_INT < 33 || context == null || displayId != 1 || !"samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER)) throw new SecurityException("Not a supported Samsung cover");
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (!SystemAppDisplay.target(display, request) || display.getState() != Display.STATE_ON || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("Cover unavailable");
        return display;
    }
    private static Class<?> tokenType() throws Exception { return Class.forName("android.window.WindowContainerToken"); }
    private static Class<?> managerType() throws Exception { return Class.forName("android.app.IActivityTaskManager"); }
    private void pose(Session session) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33) throw new IllegalStateException("Unsupported surface API");
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setPosition(session.leash, session.bounds.x(), session.bounds.y()).setCrop(session.leash, new Rect(0, 0, session.bounds.width(), session.bounds.height()));
            transaction.getClass().getMethod("show", SurfaceControl.class).invoke(transaction, session.leash);
            transaction.apply();
        }
    }
    private synchronized void settled(Session session) {
        if (current != session) return;
        session.settled = true;
        try { available(session.display, session.request); pose(session); inspect(session); }
        catch (Exception error) { android.util.Log.w("CoverPortrait", "Startup area unavailable", error); close(); }
    }
    private void watch(Session session) throws Exception {
        session.thread = new HandlerThread("CoverPortrait"); session.thread.start(); session.handler = new Handler(session.thread.getLooper());
        session.displays = new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { }
            public void onDisplayRemoved(int id) { changed(id); }
            public void onDisplayChanged(int id) { changed(id); }
            private void changed(int id) {
                synchronized (SystemAppPortrait.this) {
                    if (current != session || id != session.display) return;
                    try { if (available(id, session.request).getRotation() != session.rotation) close(); }
                    catch (Exception error) { close(); }
                }
            }
        };
        context.getSystemService(DisplayManager.class).registerDisplayListener(session.displays, session.handler);
        Class<?> listenerType = Class.forName("android.app.ITaskStackListener");
        java.lang.reflect.Method transactionName = Class.forName("android.app.ITaskStackListener$Stub").getMethod("getDefaultTransactionName", int.class);
        Binder binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("android.app.ITaskStackListener"); return true; }
                if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface("android.app.ITaskStackListener");
                try {
                    String event = (String) transactionName.invoke(null, code);
                    if ("onTaskMovedToFront".equals(event) || "onActivityRestartAttempt".equals(event)) taskFront(session, data.readTypedObject(android.app.ActivityManager.RunningTaskInfo.CREATOR));
                    if ("onTaskStackChanged".equals(event) || "onTaskMovedToFront".equals(event) || "onTaskRemoved".equals(event) || "onTaskFocusChanged".equals(event) || "onTaskDisplayChanged".equals(event)) schedule(session);
                } catch (Exception ignored) { schedule(session); }
                return true;
            }
        };
        session.listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (proxy, method, args) -> {
            if (method.getName().equals("asBinder")) return binder;
            if (method.getName().equals("toString")) return "CoverPortraitListener";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if ((method.getName().equals("onTaskMovedToFront") || method.getName().equals("onActivityRestartAttempt")) && args != null && args.length > 0) taskFront(session, args[0]);
            schedule(session); return null;
        });
        binder.attachInterface((IInterface) session.listener, "android.app.ITaskStackListener");
        managerType().getMethod("registerTaskStackListener", listenerType).invoke(session.manager, session.listener);
    }
    private void taskFront(Session session, Object task) throws Exception {
        if (task == null || task.getClass().getField("displayId").getInt(task) != session.display || task.getClass().getField("displayAreaFeatureId").getInt(task) == session.feature) return;
        // A different area can resume Home underneath ours while ours remains focused.
        // Honor the explicit task-front event so the owned area cannot cover that navigation.
        session.handler.post(() -> { synchronized (SystemAppPortrait.this) { if (current == session) close(); } });
    }
    private synchronized void schedule(Session session) {
        if (current != session || !session.settled || session.check != null) return;
        session.check = () -> {
            synchronized (SystemAppPortrait.this) {
                session.check = null;
                if (current != session) return;
                try { inspect(session); } catch (Exception error) { android.util.Log.w("CoverPortrait", "Area no longer available", error); close(); }
            }
        };
        session.handler.postDelayed(session.check, 150);
    }
    private void inspect(Session session) throws Exception {
        if (available(session.display, session.request).getRotation() != session.rotation) { close(); return; }
        List<?> roots = (List<?>) managerType().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(session.manager, session.display);
        boolean visible = false;
        for (Object root : roots) {
            if (root.getClass().getField("displayAreaFeatureId").getInt(root) != session.feature || root.getClass().getField("userId").getInt(root) != user) continue;
            visible |= root.getClass().getField("isVisible").getBoolean(root) && root.getClass().getField("isFocused").getBoolean(root);
        }
        if (!visible) close();
    }
    synchronized void close() {
        Session session = current; current = null;
        if (session == null) return;
        if (session.handler != null) session.handler.removeCallbacksAndMessages(null);
        if (session.displays != null) context.getSystemService(DisplayManager.class).unregisterDisplayListener(session.displays);
        if (session.listener != null) try { managerType().getMethod("unregisterTaskStackListener", Class.forName("android.app.ITaskStackListener")).invoke(session.manager, session.listener); } catch (Exception ignored) { }
        if (session.token != null) try { session.organizer.getClass().getMethod("deleteTaskDisplayArea", tokenType()).invoke(session.organizer, session.token); } catch (Exception error) { android.util.Log.w("CoverPortrait", "Area deletion failed; releasing its organizer", error); }
        if (session.organizer != null) try { session.organizer.getClass().getMethod("unregisterOrganizer").invoke(session.organizer); } catch (Exception ignored) { /* Binder death is the final owner cleanup. */ }
        if (session.leash != null) session.leash.release();
        if (session.thread != null) session.thread.quitSafely();
    }
    private static final class Session {
        final int display, rotation; final JSONObject request; final String name, id;
        Object organizer, token, manager, listener; SurfaceControl leash; AppPortraitBounds.Bounds bounds;
        HandlerThread thread; Handler handler; DisplayManager.DisplayListener displays; Runnable check;
        int feature; boolean settled;
        Session(int display, int rotation, JSONObject request, String name, String id) { this.display = display; this.rotation = rotation; this.request = request; this.name = name; this.id = id; }
    }
}
