package io.github.flipcover.controls;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Samsung's per-package cover compatibility protocol; never changes a display's density. */
final class AppDisplayPolicy {
    static final String KEY = "multistar_cover_widget_policy_list";
    static final int DEFAULT = 2, CUSTOM = -1;
    static final int MIN_DPI = 120, MAX_DPI = 960;
    static final double MIN_SCALE = .5, MAX_SCALE = 4;
    private static final double[] RATIOS = {BuildConfig.APPEARANCE_APP_DISPLAY_SMALLER_RATIO, BuildConfig.APPEARANCE_APP_DISPLAY_SMALL_RATIO, 1, BuildConfig.APPEARANCE_APP_DISPLAY_LARGE_RATIO, BuildConfig.APPEARANCE_APP_DISPLAY_LARGER_RATIO};
    record Config(int dpi, double scale) {
        double effectiveDpi() { return dpi / scale; }
        void validate() {
            if (dpi < MIN_DPI || dpi > MAX_DPI) throw new IllegalArgumentException("dpi");
            if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE || scale == 1) throw new IllegalArgumentException("scale");
            if (effectiveDpi() < 120) throw new IllegalArgumentException("size_small");
            if (effectiveDpi() > 960) throw new IllegalArgumentException("size_large");
        }
    }
    static void packageName(String name) {
        if (name == null || name.length() > 255 || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("invalid package");
    }
    static String encodedPackage(String name) throws Exception {
        packageName(name);
        // Samsung uses these literal hexadecimal bytes; this is NOT decimal 1..16.
        byte[] key = java.util.Arrays.copyOf("goodlock_multistar".getBytes(StandardCharsets.UTF_8), 16), iv = {1, 2, 3, 4, 5, 6, 7, 8, 9, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16};
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(cipher.doFinal(name.getBytes(StandardCharsets.UTF_8)));
    }
    static Config config(String entry) {
        String[] fields = entry.split(",", -1);
        if (fields.length != 3) throw new IllegalArgumentException("invalid policy entry");
        int dpi = Integer.parseInt(fields[1]); double scale = Double.parseDouble(fields[2]);
        // Existing Samsung sentinel densities are valid snapshots, not editable input.
        if (dpi < -1 || dpi > 4096 || !Double.isFinite(scale) || scale < .1 || scale > 16) throw new IllegalArgumentException("unknown policy format");
        byte[] encoded = Base64.getDecoder().decode(fields[0]);
        if (encoded.length == 0 || encoded.length % 16 != 0) throw new IllegalArgumentException("invalid package encoding");
        return new Config(dpi, scale);
    }
    static String entry(String name, Config config) throws Exception { config.validate(); return encodedPackage(name) + "," + config.dpi() + "," + config.scale(); }
    private static List<String> entries(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        if (raw.length() > 60000) throw new IllegalArgumentException("policy too large");
        for (String entry : raw.split(";", -1)) { if (entry.isEmpty()) throw new IllegalArgumentException("unknown policy format"); config(entry); result.add(entry); }
        return result;
    }
    static String find(String raw, String name) throws Exception {
        String prefix = encodedPackage(name) + ",", found = "";
        for (String entry : entries(raw)) if (entry.startsWith(prefix)) { if (!found.isEmpty()) throw new IllegalArgumentException("duplicate package policy"); found = entry; }
        return found;
    }
    static String replace(String raw, String name, String wanted) throws Exception {
        String prefix = encodedPackage(name) + ",";
        if (!wanted.isEmpty()) { config(wanted); if (!wanted.startsWith(prefix)) throw new IllegalArgumentException("wrong package policy"); }
        find(raw, name); List<String> all = entries(raw); int index = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).startsWith(prefix)) index = i;
        if (index >= 0) { if (wanted.isEmpty()) all.remove(index); else all.set(index, wanted); }
        else if (!wanted.isEmpty()) all.add(wanted);
        return all.isEmpty() && raw == null ? null : String.join(";", all);
    }
    static Config baseline(String entry) {
        // MultiStar's common reference when no explicit package entry exists. UI labels this as a suggestion.
        if (entry.isEmpty()) return reference();
        Config stored = config(entry);
        if (stored.dpi() <= 0) throw new IllegalArgumentException("system density needs explicit configuration");
        // Scale 1 is a Samsung sentinel: the framework derives density / 340 instead.
        return new Config(stored.dpi(), stored.scale() == 1 ? stored.dpi() / 340.0 : stored.scale());
    }
    static Config reference() { return new Config(BuildConfig.APPEARANCE_APP_DISPLAY_REFERENCE_DPI, Double.parseDouble(Float.toString(BuildConfig.APPEARANCE_APP_DISPLAY_REFERENCE_SCALE))); }
    static Config preset(String baseline, int index) {
        if (index < 0 || index >= RATIOS.length || index == DEFAULT) throw new IllegalArgumentException("default restores the snapshot");
        Config base = baseline(baseline);
        // Width-adapting apps can replace DPI themselves. Keep it stable and change
        // Samsung's task compatibility scale instead, always relative to the original.
        double scale = Math.round(base.scale() / RATIOS[index] * 1000) / 1000.0;
        // Avoid rounding onto Samsung's special value; retain the intended near-1 scale.
        if (scale == 1) scale = 1.001;
        Config value = new Config(base.dpi(), scale);
        value.validate(); return value;
    }
    static int selectedPreset(String original, String applied, int saved) {
        if (saved == DEFAULT) return applied.equals(original) ? DEFAULT : CUSTOM;
        if (saved < 0 || saved >= RATIOS.length) return CUSTOM;
        try { return config(applied).equals(preset(original, saved)) ? saved : CUSTOM; }
        catch (RuntimeException ignored) { return CUSTOM; }
    }
    interface Access { String read() throws Exception; void write(String raw) throws Exception; void check() throws Exception; }
    static final class Conflict extends Exception { Conflict() { super("policy changed"); } }
    /** Serial service operation. Re-reads preserve unrelated entries changed since the UI was opened. */
    static String change(Access access, String name, String expected, String wanted) throws Exception {
        access.check(); String before = access.read();
        if (!find(before, name).equals(expected)) throw new Conflict();
        String after = replace(before, name, wanted);
        if (Objects.equals(before, after)) return wanted;
        access.check();
        if (!Objects.equals(before, access.read())) throw new Conflict();
        try {
            access.write(after); access.check();
            if (!Objects.equals(after, access.read())) throw new IllegalStateException("policy readback mismatch");
            return wanted;
        } catch (Exception failure) {
            // Only undo our target, and never overwrite an intervening external edit.
            try { String current = access.read(); if (find(current, name).equals(wanted)) access.write(replace(current, name, expected)); }
            catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
    static boolean member(String list, String name, int user, boolean withUser) {
        if (list == null) return false;
        for (String entry : list.split(";")) if (entry.equals(withUser ? name + "," + user : name)) return true;
        return false;
    }
}
