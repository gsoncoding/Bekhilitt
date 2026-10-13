package dev.bekhilitt.platform.story;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure, bounded scenery plan. Coordinates are relative to the unchanged story floor. */
public final class WarCampPlan {
    public static final int MIN_X = -28, MAX_X = 28, MIN_Z = -28, MAX_Z = 28;
    public static final int MIN_Y_OFFSET = -5, MAX_Y_OFFSET = 12;
    public static final int BLOCK_BUDGET = 32000;
    public record Cell(int x, int y, int z) { }
    public enum Material {
        STONE, ANDESITE, COBBLESTONE, MOSSY_COBBLESTONE, CRACKED_STONE_BRICKS,
        COARSE_DIRT, PODZOL, GRAVEL, MOSS_BLOCK, SPRUCE_LOG, SPRUCE_PLANKS,
        SPRUCE_FENCE, SPRUCE_LEAVES, WHITE_WOOL, LIGHT_GRAY_WOOL, GRAY_WOOL,
        BROWN_CARPET, LANTERN, CAMPFIRE, HAY_BLOCK
    }
    private final long seed;
    private final Map<Cell, Material> blocks = new LinkedHashMap<>();
    private WarCampPlan(long seed) { this.seed = seed; }

    public static Map<Cell, Material> layout(long seed) {
        WarCampPlan p = new WarCampPlan(seed);
        p.terrain();
        p.recoveryTent();
        p.smallTent(15, -13, Material.LIGHT_GRAY_WOOL);
        p.smallTent(-17, 8, Material.GRAY_WOOL);
        p.ruins();
        p.forest();
        p.campDetails();
        if (p.blocks.size() > BLOCK_BUDGET) throw new IllegalStateException("War camp block budget exceeded");
        return Collections.unmodifiableMap(p.blocks);
    }

    public static boolean fitsBuildHeight(int floor, int minInclusive, int maxExclusive) {
        return (long) floor + MIN_Y_OFFSET >= minInclusive
            && (long) floor + MAX_Y_OFFSET < maxExclusive;
    }

    /** Fixed playable centre, varied outer banks; variation never moves a story anchor. */
    private int height(int x, int z) {
        int edge = Math.max(Math.abs(x) - 16, Math.abs(z) - 13);
        return edge <= 0 ? 0 : Math.min(3, 1 + edge / 5 + sample(x / 4, z / 4, 3, 2));
    }
    private void terrain() {
        for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++) {
            int top = height(x, z);
            for (int y = MIN_Y_OFFSET; y < top; y++) {
                Material m = sample(x, z, y, 5) == 0 ? Material.ANDESITE : Material.STONE;
                if (y >= top - 2) m = Material.COARSE_DIRT;
                put(x, y, z, m);
            }
            boolean path = (x >= -5 && x <= -2 && z <= 0) || (z >= -2 && z <= 0 && x >= -9 && x <= 7);
            boolean duel = x >= -3 && x <= 11 && z >= 1 && z <= 11;
            int noise = sample(x, z, 7, 10);
            put(x, top, z, path ? (noise < 3 ? Material.GRAVEL : Material.COARSE_DIRT)
                : duel ? (noise == 0 ? Material.GRAVEL : Material.COARSE_DIRT)
                : noise < 2 ? Material.MOSS_BLOCK : noise < 6 ? Material.PODZOL : Material.COARSE_DIRT);
            // Low, irregular stone bank masks the bounded set from its playable centre.
            if (Math.abs(x) == 28 || Math.abs(z) == 28)
                for (int y = top + 1; y <= top + 2 + sample(x, z, 9, 2); y++)
                    put(x, y, z, sample(x, z, y, 3) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
        }
    }
    private void recoveryTent() {
        for (int x = -13; x <= -6; x++) for (int z = -8; z <= -3; z++) {
            if (z < -3 || x == -13 || x == -6) put(x, 1, z, Material.SPRUCE_PLANKS);
            if (x == -13 || x == -6 || z == -8)
                for (int y = 2; y <= 4; y++) put(x, y, z, Material.LIGHT_GRAY_WOOL);
            int roof = 5 + Math.min(x + 13, -6 - x) / 2;
            put(x, roof, z, sample(x, z, 12, 7) == 0 ? Material.GRAY_WOOL : Material.WHITE_WOOL);
        }
        for (int z = -7; z <= -5; z++) put(-10, 2, z, Material.BROWN_CARPET);
        for (int x : new int[]{-13, -6}) {
            for (int y = 2; y <= 5; y++) put(x, y, -3, Material.SPRUCE_LOG);
        }
        put(-12, 2, -7, Material.LANTERN);
    }
    private void smallTent(int cx, int cz, Material canvas) {
        int floor = 0;
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++)
            floor = Math.max(floor, height(x, z));
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
            for (int y = height(x, z) + 1; y <= floor; y++) put(x, y, z, Material.COARSE_DIRT);
            put(x, floor + 1, z, Material.SPRUCE_PLANKS);
            if (x == cx - 2 || x == cx + 2 || z == cz - 2)
                for (int y = floor + 2; y <= floor + 3; y++) put(x, y, z, canvas);
            put(x, floor + 4 + (x == cx ? 1 : 0), z, canvas);
        }
    }
    private void ruins() {
        for (int x = 5; x <= 18; x++) {
            int base = height(x, -22), tall = 2 + sample(x, -22, 17, 4);
            for (int y = base + 1; y <= base + tall; y++) {
                if (x >= 10 && x <= 12 && y < base + 4) continue; // broken gate silhouette
                put(x, y, -22, sample(x, y, 19, 3) == 0 ? Material.MOSSY_COBBLESTONE : Material.CRACKED_STONE_BRICKS);
            }
        }
        for (int z = -24; z <= -19; z++) for (int y = height(18, z) + 1; y <= 10; y++)
            put(18, y, z, sample(z, y, 23, 3) == 0 ? Material.COBBLESTONE : Material.CRACKED_STONE_BRICKS);
    }
    private void forest() {
        for (int x = -24; x <= 24; x += 6) for (int z = -24; z <= 24; z += 6) {
            if (Math.abs(x) < 21 && Math.abs(z) < 19) continue;
            if (z < -18 && x > 2) continue; // the ruin stays readable
            int tx = x + sample(x, z, 31, 3) - 1, tz = z + sample(x, z, 37, 3) - 1;
            int base = height(tx, tz), trunk = 5 + sample(tx, tz, 41, 3);
            for (int y = base + 1; y <= base + trunk; y++) put(tx, y, tz, Material.SPRUCE_LOG);
            for (int dy = 2; dy <= trunk + 1; dy++) {
                int radius = dy >= trunk ? 1 : 2;
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1 || (dx == 0 && dz == 0 && dy <= trunk)) continue;
                    put(tx + dx, base + dy, tz + dz, Material.SPRUCE_LEAVES);
                }
            }
        }
    }
    private void campDetails() {
        put(-2, 1, -5, Material.CAMPFIRE);
        for (int x = -4; x <= 0; x++) put(x, 1, -7, Material.SPRUCE_LOG);
        for (int z = -8; z <= -5; z++) put(12, 1, z, Material.SPRUCE_LOG);
        for (int x : new int[]{-14, 14}) for (int z : new int[]{-10, 10}) {
            put(x, 1, z, Material.SPRUCE_LOG); put(x, 2, z, Material.LANTERN);
        }
        for (int x = -20; x <= -18; x++) put(x, height(x, -7) + 1, -7, Material.HAY_BLOCK);
        // A short equipment rail and supply stack, deliberately outside every dialogue corridor.
        for (int x = 9; x <= 13; x++) put(x, 1, -11, Material.SPRUCE_FENCE);
        put(11, 1, -10, Material.SPRUCE_PLANKS); put(11, 2, -10, Material.SPRUCE_PLANKS);
    }
    private void put(int x, int y, int z, Material material) {
        if (x < MIN_X || x > MAX_X || z < MIN_Z || z > MAX_Z || y < MIN_Y_OFFSET || y > MAX_Y_OFFSET)
            throw new IllegalArgumentException("Out of scenery bounds: " + x + "," + y + "," + z);
        blocks.put(new Cell(x, y, z), material);
    }
    private int sample(int x, int z, int salt, int bound) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (int) Long.remainderUnsigned(h ^ (h >>> 31), bound);
    }
}
