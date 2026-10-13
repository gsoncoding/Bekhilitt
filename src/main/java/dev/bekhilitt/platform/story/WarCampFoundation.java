package dev.bekhilitt.platform.story;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import static dev.bekhilitt.platform.story.WarCampPlan.*;

/** Pure support-only plan. Returned cells use absolute Y; this class never edits a world. */
public final class WarCampFoundation {
    public static final int MAX_FOUNDATION_DEPTH = 64;
    public static final int MAX_TOTAL_BLOCKS = 100000;
    public record Column(int x, int z) { }
    private WarCampFoundation() { }

    /**
     * Fills [groundFirstAir, lowestLayoutAbsoluteY) in each occupied column.
     * groundFirstAir must be the ORIGINAL terrain sample, persisted before any placement.
     * The caller must validate world build height and preflight all support cells as air.
     */
    public static Map<Cell, Material> plan(Map<Cell, Material> relativeLayout, int floor,
                                         Map<Column, Integer> groundFirstAir) {
        Objects.requireNonNull(relativeLayout, "relativeLayout");
        Objects.requireNonNull(groundFirstAir, "groundFirstAir");
        if (relativeLayout.isEmpty() || relativeLayout.size() > MAX_TOTAL_BLOCKS)
            throw new IllegalArgumentException("Invalid layout size");
        var lowest = new LinkedHashMap<Column, Integer>();
        for (var entry : relativeLayout.entrySet()) {
            Cell c = Objects.requireNonNull(entry.getKey(), "layout cell");
            Objects.requireNonNull(entry.getValue(), "layout material");
            if (c.x() < MIN_X || c.x() > MAX_X || c.z() < MIN_Z || c.z() > MAX_Z
                    || c.y() < MIN_Y_OFFSET || c.y() > MAX_Y_OFFSET)
                throw new IllegalArgumentException("Layout outside reserved region");
            long absolute = (long) floor + c.y();
            if (absolute < Integer.MIN_VALUE || absolute > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Absolute layout height overflow");
            lowest.merge(new Column(c.x(), c.z()), (int) absolute, Math::min);
        }
        if (!lowest.keySet().equals(groundFirstAir.keySet()))
            throw new IllegalArgumentException("Terrain samples must match occupied columns exactly");
        long total = relativeLayout.size();
        // Validate the COMPLETE input before allocating support blocks.
        for (var entry : lowest.entrySet()) {
            Integer ground = groundFirstAir.get(entry.getKey());
            if (ground == null) throw new IllegalArgumentException("Missing terrain height");
            long depth = (long) entry.getValue() - ground;
            if (depth < 0 || depth > MAX_FOUNDATION_DEPTH)
                throw new IllegalArgumentException("Foundation depth outside 0.." + MAX_FOUNDATION_DEPTH);
            total += depth;
            if (total > MAX_TOTAL_BLOCKS) throw new IllegalArgumentException("Combined camp block budget exceeded");
        }
        var support = new LinkedHashMap<Cell, Material>();
        for (var entry : lowest.entrySet()) {
            Column c = entry.getKey();
            // long loop counter also handles Integer.MAX_VALUE without wrapping.
            for (long y = groundFirstAir.get(c); y < entry.getValue(); y++) {
                long hash = c.x() * 73428767L ^ c.z() * 912931L ^ y * 438289L;
                support.put(new Cell(c.x(), (int) y, c.z()), (hash & 7) == 0 ? Material.ANDESITE : Material.STONE);
            }
        }
        return Collections.unmodifiableMap(support);
    }
}
