package dev.bekhilitt.platform.story;

import java.util.HashMap;
import java.util.Map;
import static dev.bekhilitt.platform.story.WarCampPlan.*;
import static dev.bekhilitt.platform.story.WarCampFoundation.*;

/** Dependency-free foundation safety contract. */
public final class WarCampFoundationSelfTest {
    private WarCampFoundationSelfTest() { }
    public static void main(String[] args) { runAll(); }
    public static void runAll() {
        var layout = Map.of(new Cell(0, -5, 0), Material.STONE, new Cell(0, 2, 0), Material.WHITE_WOOL);
        var samples = Map.of(new Column(0, 0), 50);
        var supports = plan(layout, 64, samples);
        require(supports.size() == 9, "exact depth");
        for (int y = 50; y < 59; y++) require(supports.containsKey(new Cell(0, y, 0)), "continuous support column");
        require(!supports.containsKey(new Cell(0, 59, 0)), "never replaces planned lowest block");
        require(!supports.containsKey(new Cell(0, 49, 0)), "never replaces ground");
        require(supports.equals(plan(layout, 64, samples)), "deterministic");
        require(plan(layout, 64, Map.of(new Column(0, 0), 59)).isEmpty(), "zero depth");
        require(plan(layout, 64, Map.of(new Column(0, 0), -5)).size() == 64, "maximum allowed depth");
        rejects(() -> plan(layout, 64, Map.of(new Column(0, 0), -6)));
        rejects(() -> plan(layout, 64, Map.of(new Column(0, 0), 60)));
        rejects(() -> plan(layout, 64, Map.of()));
        rejects(() -> plan(layout, 64, Map.of(new Column(1, 0), 50)));
        rejects(() -> plan(layout, 64, Map.of(new Column(0, 0), 50, new Column(1, 0), 50)));
        rejects(() -> plan(Map.of(), 64, Map.of()));
        rejects(() -> plan(Map.of(new Cell(29, -5, 0), Material.STONE), 64, samples));
        rejects(() -> plan(Map.of(new Cell(0, 13, 0), Material.STONE), 64, samples));
        rejects(() -> plan(layout, Integer.MAX_VALUE, samples));
        rejects(() -> plan(layout, Integer.MIN_VALUE, samples));
        var nullSample = new HashMap<Column, Integer>(); nullSample.put(new Column(0, 0), null);
        rejects(() -> plan(layout, 64, nullSample));
        try { supports.clear(); throw new AssertionError("mutable support plan"); }
        catch (UnsupportedOperationException expected) { }
        var full = layout(1234);
        var uneven = new HashMap<Column, Integer>();
        for (Cell c : full.keySet()) uneven.put(new Column(c.x(), c.z()), 53 + Math.floorMod(c.x() + c.z(), 6));
        var fullSupports = plan(full, 64, uneven);
        for (var e : fullSupports.entrySet()) {
            Cell c = e.getKey();
            require(c.y() >= uneven.get(new Column(c.x(), c.z())) && c.y() < 59, "support stays above original terrain and below set");
            require(e.getValue() == Material.STONE || e.getValue() == Material.ANDESITE, "non-falling foundation palette");
        }
        var deep = new HashMap<Column, Integer>();
        uneven.keySet().forEach(c -> deep.put(c, -5));
        rejects(() -> plan(full, 64, deep)); // individually valid depth, aggregate is too large
        require(full.size() + fullSupports.size() <= MAX_TOTAL_BLOCKS, "combined budget");
        System.out.println("WarCampFoundation: continuity, original-ground preservation, depth, aggregate budget, invalid input, overflow and immutability PASS; uneven-ground support blocks: " + fullSupports.size());
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("unsafe plan accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
