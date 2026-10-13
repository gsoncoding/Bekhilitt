package dev.bekhilitt.platform.story;

import java.util.Map;
import static dev.bekhilitt.platform.story.WarCampPlan.*;

/** Dependency-free tests, also called by JUnit. */
public final class WarCampPlanSelfTest {
    private WarCampPlanSelfTest() { }
    public static void main(String[] args) { runAll(); }
    public static void runAll() {
        var first = layout(42);
        require(first.equals(layout(42)), "same seed must recreate exactly the same layout");
        require(!first.equals(layout(43)), "different world seeds must vary scenery");
        try { first.clear(); throw new AssertionError("mutable plan"); }
        catch (UnsupportedOperationException expected) { }
        require(fitsBuildHeight(256, -64, 320), "overworld height");
        require(!fitsBuildHeight(0, 0, 256), "minimum height refusal");
        require(!fitsBuildHeight(256, 0, 256), "maximum height refusal");
        require(!fitsBuildHeight(Integer.MAX_VALUE, -64, 320), "overflow safe height refusal");
        int maximum = 0;
        for (long seed = -64; seed < 64; seed++) {
            Map<Cell, Material> plan = layout(seed);
            maximum = Math.max(maximum, plan.size());
            require(plan.size() <= BLOCK_BUDGET, "bounded block budget");
            for (Cell c : plan.keySet()) require(c.x() >= MIN_X && c.x() <= MAX_X
                && c.z() >= MIN_Z && c.z() <= MAX_Z && c.y() >= MIN_Y_OFFSET && c.y() <= MAX_Y_OFFSET,
                "all blocks stay inside preflight volume");
            for (int x = -3; x <= 11; x++) for (int z = 0; z <= 11; z++) {
                require(plan.containsKey(new Cell(x, 0, z)), "duel has continuous floor");
                for (int y = 1; y <= 4; y++) require(!plan.containsKey(new Cell(x, y, z)), "duel clearance");
            }
            for (int x = -10; x <= 7; x++) for (int z = -2; z <= -1; z++)
                for (int y = 1; y <= 3; y++) require(!plan.containsKey(new Cell(x, y, z)), "dialogue corridor");
            clear(plan, -8, 1, -3); // Casca
            clear(plan, 2, 1, -3); // Griffith
            clear(plan, -12, 2, -6); // recovery camera
            clear(plan, -9, 2, -6); // recovery Casca
            require(plan.get(new Cell(-10, 1, -5)) == Material.SPRUCE_PLANKS, "wake floor remains fixed");
            require(plan.get(new Cell(-10, 2, -5)) == Material.BROWN_CARPET, "wake sleeping mat");
            for (int x = -12; x <= -7; x++) clear(plan, x, 2, -3); // open tent front
        }
        System.out.println("WarCampPlan: 128 seeds; determinism, variation, immutability, bounds, height, story anchors, tent entry and duel clearances PASS. Maximum blocks: " + maximum);
    }
    private static void clear(Map<Cell, Material> plan, int x, int y, int z) {
        require(!plan.containsKey(new Cell(x, y, z)) && !plan.containsKey(new Cell(x, y + 1, z)), "story anchor clearance");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
