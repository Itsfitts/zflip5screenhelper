package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Parcel;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Deterministic callback and drawing checks; never starts or changes a real VPN. */
final class StatusVpnChecks {
    static String run(Instrumentation test) {
        int[] assertions = {0};
        test.runOnMainSync(() -> {
            try {
                Prefs prefs = new Prefs(test.getTargetContext());
                for (boolean panel : new boolean[]{false, true}) {
                    StatusBarView view = new StatusBarView(test.getTargetContext(), prefs, panel);
                    view.onAttachedToWindow();
                    try {
                        ConnectivityManager.NetworkCallback callback = callback(view);
                        require(callback != null && !active(view), "attachment starts without a stale VPN", assertions);
                        Network first = network(65101), second = network(65102);
                        callback.onAvailable(first); callback.onAvailable(first); callback.onAvailable(second);
                        callback.onLost(first);
                        require(active(view), "losing one network preserves the other VPN", assertions);
                        callback.onLost(second);
                        require(!active(view), "last network lost clears the indicator, including duplicate available events", assertions);
                        for (int scale : new int[]{50, 100, 150}) for (int width : new int[]{180, 340}) {
                            view.previewScale(scale); view.previewSafeArea(16, 16);
                            view.layout(0, 0, Ui.dp(test.getTargetContext(), width), view.heightPixels());
                            Bitmap disconnected = render(view);
                            callback.onAvailable(first);
                            Bitmap connected = render(view);
                            require(!connected.sameAs(disconnected), "VPN adds visible pixels in both hosts and all tested sizes", assertions);
                            callback.onLost(first);
                            Bitmap restored = render(view);
                            require(restored.sameAs(disconnected), "disconnect restores the exact original layout", assertions);
                            if (scale == 100 && width == 340) {
                                File directory = new File(test.getTargetContext().getFilesDir(), "status-vpn-raw");
                                if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create VPN render directory");
                                try (FileOutputStream output = new FileOutputStream(new File(directory, panel ? "panel.png" : "overlay.png"))) { connected.compress(Bitmap.CompressFormat.PNG, 100, output); }
                            }
                            disconnected.recycle(); connected.recycle(); restored.recycle();
                        }
                        callback.onAvailable(first);
                        view.onDetachedFromWindow();
                        require(!active(view) && callback(view) == null, "detach releases VPN state and subscription identity", assertions);
                        callback.onAvailable(second);
                        require(!active(view), "late callback cannot restore detached indicator", assertions);
                        view.onAttachedToWindow();
                        ConnectivityManager.NetworkCallback current = callback(view);
                        require(current != null && current != callback, "reattach creates a new callback identity", assertions);
                        callback.onAvailable(first);
                        require(!active(view), "old callback cannot populate the new session", assertions);
                        current.onAvailable(second); callback.onLost(second);
                        require(active(view), "old loss cannot clear current VPN", assertions);
                        current.onLost(second);
                    } finally { view.onDetachedFromWindow(); }
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
        return "PASS: VPN status; " + assertions[0] + " assertions; injected network callbacks, native renders and real registration/release; not Samsung VPN routing validation";
    }
    private static Object field(StatusBarView view, String name) throws Exception { Field field = StatusBarView.class.getDeclaredField(name); field.setAccessible(true); return field.get(view); }
    private static Network network(int id) { Parcel parcel = Parcel.obtain(); try { parcel.writeInt(id); parcel.setDataPosition(0); return Network.CREATOR.createFromParcel(parcel); } finally { parcel.recycle(); } }
    private static boolean active(StatusBarView view) throws Exception { return (boolean) field(view, "vpn"); }
    private static ConnectivityManager.NetworkCallback callback(StatusBarView view) throws Exception { return (ConnectivityManager.NetworkCallback) field(view, "vpnNetworks"); }
    private static Bitmap render(StatusBarView view) { Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap)); return bitmap; }
    private static void require(boolean condition, String message, int[] assertions) { assertions[0]++; if (!condition) throw new AssertionError(message); }
}
