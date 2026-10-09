package io.github.flipcover.controls;

import android.bluetooth.BluetoothAdapter;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

/** Reads actual services as Shizuku's shell UID; watches only while a control surface is visible. */
final class SystemSwitches implements AutoCloseable {
    static final java.util.List<String> IDS = java.util.List.of("wifi", "bluetooth", "data", "dnd", "airplane");
    private Object bluetooth, bluetoothCallback;
    private Class<?> callbackType;
    private String unregister;
    private volatile IConnectivityListener listener;
    private volatile int generation;
    private final IBinder.DeathRecipient death = this::close;
    private static Object call(String service, String contract, String method, Class<?>[] types, Object... args) throws Exception {
        return Class.forName(contract).getMethod(method, types).invoke(SystemConnectivity.service(service, contract), args);
    }
    int dataSubscription() throws Exception {
        int subscription = (int) call("isub", "com.android.internal.telephony.ISub", "getDefaultDataSubId", new Class<?>[0]);
        if (subscription < 0 || !(boolean) call("isub", "com.android.internal.telephony.ISub", "isActiveSubId", new Class<?>[]{int.class, String.class, String.class}, subscription, "com.android.shell", null)) return -1;
        return subscription;
    }
    int state(String id) {
        try {
            int subscription = id.equals("data") ? dataSubscription() : -1;
            int state = state(id, subscription);
            return id.equals("data") && subscription != dataSubscription() ? -1 : state;
        } catch (Exception error) { return -1; }
    }
    int state(String id, int subscription) throws Exception {
        return switch (id) {
            case "wifi" -> wifiState((int) call("wifi", "android.net.wifi.IWifiManager", "getWifiEnabledState", new Class<?>[0]));
            case "bluetooth" -> bluetoothState((int) call("bluetooth_manager", "android.bluetooth.IBluetoothManager", "getState", new Class<?>[0]));
            case "data" -> subscription < 0 ? -1 : (boolean) call("phone", "com.android.internal.telephony.ITelephony", "isUserDataEnabled", new Class<?>[]{int.class}, subscription) ? 1 : 0;
            case "dnd" -> zenState((int) call("notification", "android.app.INotificationManager", "getZenMode", new Class<?>[0]));
            case "airplane" -> ShellService.airplaneState();
            default -> -1;
        };
    }
    void dataEnabled(int subscription, boolean enabled) throws Exception {
        if (subscription < 0 || subscription != dataSubscription()) throw new IllegalStateException("数据 SIM 已改变");
        if (android.os.Build.VERSION.SDK_INT >= 31) call("phone", "com.android.internal.telephony.ITelephony", "setDataEnabledForReason", new Class<?>[]{int.class, int.class, boolean.class, String.class}, subscription, 0, enabled, "com.android.shell");
        else call("phone", "com.android.internal.telephony.ITelephony", "setUserDataEnabled", new Class<?>[]{int.class, boolean.class}, subscription, enabled);
    }
    static int wifiState(int value) { return switch (value) { case WifiManager.WIFI_STATE_ENABLED -> 1; case WifiManager.WIFI_STATE_DISABLED -> 0; default -> -1; }; }
    static int bluetoothState(int value) { return switch (value) { case BluetoothAdapter.STATE_ON -> 1; case BluetoothAdapter.STATE_OFF, 15 /* BLE-only, normal Bluetooth is off */ -> 0; default -> -1; }; }
    static int binaryState(int value) { return value == 0 || value == 1 ? value : -1; }
    static int zenState(int value) { return value >= 0 && value <= 3 ? value == 0 ? 0 : 1 : -1; }
    static boolean settingChanged(String key) { return key == null || key.equals("wifi_on") || key.equals("bluetooth_on") || key.equals("zen_mode") || key.equals("airplane_mode_on") || key.equals("multi_sim_data_call") || key.startsWith("mobile_data"); }

    interface Access {
        boolean valid();
        int read() throws Exception;
        boolean write(boolean enabled) throws Exception;
        default void await() { SystemClock.sleep(100); }
    }
    record Result(boolean ok, int state, String message) { }
    static Result change(int value, Access access) throws Exception {
        if (value < -1 || value > 1) throw new IllegalArgumentException("Expected toggle, off or on");
        if (!access.valid()) return new Result(false, -1, "外屏或数据 SIM 已改变，操作已取消");
        int before = binaryState(access.read());
        if (value == -1 && before < 0) return new Result(false, -1, "状态未知或正在切换，请长按选择开启或关闭");
        int wanted = value == -1 ? 1 - before : value;
        if (!access.valid()) return new Result(false, -1, "外屏或数据 SIM 已改变，操作已取消");
        if (before == wanted) return new Result(true, before, wanted == 1 ? "已开启" : "已关闭");
        boolean accepted = access.write(wanted == 1);
        int actual = -1;
        for (int attempt = 0; attempt < 30; attempt++) {
            if (!access.valid()) return new Result(false, -1, "外屏或数据 SIM 已改变，操作已取消");
            actual = binaryState(access.read());
            if (!access.valid()) return new Result(false, -1, "外屏或数据 SIM 已改变，操作已取消");
            if (!accepted || actual == wanted) break;
            access.await();
        }
        boolean confirmed = accepted && actual == wanted;
        return new Result(confirmed, actual, confirmed ? actual == 1 ? "已开启" : "已关闭" : accepted ? "请求已提交，系统状态尚未确认，请刷新" : "系统未接受开关请求");
    }
    synchronized void watch(IConnectivityListener next) {
        close(); if (next == null) return;
        long identity = Binder.clearCallingIdentity();
        try {
            listener = next; next.asBinder().linkToDeath(death, 0); int version = generation;
            Runnable changed = () -> { if (version == generation) { IConnectivityListener current = listener; if (current != null) try { current.onChanged(); } catch (android.os.RemoteException ignored) { } } };
            Class<?> manager = Class.forName("android.bluetooth.IBluetoothManager"); java.lang.reflect.Method register;
            try {
                callbackType = Class.forName("android.bluetooth.IBluetoothStateChangeCallback");
                register = manager.getMethod("registerStateChangeCallback", callbackType); unregister = "unregisterStateChangeCallback";
            } catch (ClassNotFoundException | NoSuchMethodException modern) {
                callbackType = Class.forName("android.bluetooth.IBluetoothManagerCallback");
                // Modern versions moved the final on/off notifications into the adapter callback.
                callbackType.getMethod("onBluetoothOn"); callbackType.getMethod("onBluetoothOff");
                register = manager.getMethod("registerAdapter", callbackType); unregister = "unregisterAdapter";
            }
            String descriptor = callbackType.getName();
            Binder token = new Binder() {
                { attachInterface(null, descriptor); }
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                    if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(descriptor); return true; }
                    if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) { data.enforceInterface(descriptor); changed.run(); return true; }
                    return super.onTransact(code, data, reply, flags);
                }
            };
            bluetoothCallback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> switch (method.getName()) {
                case "asBinder" -> token; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; case "toString" -> "ControlStateCallback"; default -> null;
            });
            bluetooth = SystemConnectivity.service("bluetooth_manager", manager.getName()); register.invoke(bluetooth, bluetoothCallback);
            changed.run();
        } catch (Exception error) { close(); android.util.Log.w("SystemSwitches", "Bluetooth state callback unavailable", error); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public synchronized void close() {
        generation++; IConnectivityListener old = listener; listener = null;
        if (old != null) try { old.asBinder().unlinkToDeath(death, 0); } catch (RuntimeException ignored) { }
        if (bluetooth != null && bluetoothCallback != null) try { Class.forName("android.bluetooth.IBluetoothManager").getMethod(unregister, callbackType).invoke(bluetooth, bluetoothCallback); } catch (Exception ignored) { }
        bluetooth = null; bluetoothCallback = null; callbackType = null; unregister = null;
    }
}
