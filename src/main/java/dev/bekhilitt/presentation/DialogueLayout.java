package dev.bekhilitt.presentation;

/** Coordinates are logical GUI pixels, so Minecraft's selected GUI scale remains authoritative. */
public record DialogueLayout(int x, int y, int width, int height, int linesPerPage) {
    public static final int LINE_HEIGHT = 11;
    public static DialogueLayout fit(int screenWidth, int screenHeight, int lineCount) {
        if (screenWidth < 160 || screenHeight < 120) throw new IllegalArgumentException("Unsupported GUI viewport");
        int width = Math.min(520, screenWidth - 16);
        // At Minecraft's smallest supported logical GUI (320x240), preserve over half the world.
        int budget = Math.min(142, screenHeight * 43 / 100);
        int capacity = Math.max(1, (budget - 57) / LINE_HEIGHT);
        int rows = Math.min(Math.max(1, lineCount), capacity);
        int height = 57 + rows * LINE_HEIGHT;
        return new DialogueLayout((screenWidth - width) / 2, screenHeight - height - 6, width, height, rows);
    }
    public int textWidth() { return width - 20; }
    public int bodyY() { return y + 22; }
    public int buttonsY() { return y + height - 25; }
}
