package io.github.flipcover.controls;

/** Physical pixels inside the selected cover's cutout-safe rectangle. */
final class AppPortraitBounds {
    static final java.util.List<String> RATIOS = java.util.List.of("9:20", "9:16", "12:16", "15:16");
    static final String DEFAULT_RATIO = "12:16";
    record Bounds(int x, int y, int width, int height) { }
    static Bounds fit(int width, int height, int left, int top, int right, int bottom, String ratio) {
        double aspect = switch (ratio) { case "9:20" -> 9d / 20; case "9:16" -> 9d / 16; case "12:16" -> 12d / 16; case "15:16" -> 15d / 16; default -> throw new IllegalArgumentException("Invalid portrait ratio"); };
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || right < 0 || bottom < 0) throw new IllegalArgumentException("Invalid cover geometry");
        int availableWidth = width - left - right, availableHeight = height - top - bottom;
        if (availableWidth < 240 || availableHeight < 320) throw new IllegalArgumentException("Cover too small");
        int windowHeight = Math.min(availableHeight, (int) (availableWidth / aspect)) / 2 * 2;
        int windowWidth = (int) (windowHeight * aspect) / 2 * 2;
        return new Bounds(left + (availableWidth - windowWidth) / 2, top + (availableHeight - windowHeight) / 2, windowWidth, windowHeight);
    }
}
