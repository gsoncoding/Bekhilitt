package dev.bekhilitt.platform.story;

import java.util.UUID;

/** Local non-combat visit policy. No world rules, entity removal or inventory replacement. */
public final class CampSafetyRules {
    /** Endermen sample up to two blocks horizontally and three vertically from their position. */
    public static final int MOB_REACH_MARGIN = 4;
    private CampSafetyRules() { }

    public static boolean active(boolean hasSession, boolean visiting, boolean ready, boolean sameLevel) {
        return hasSession && visiting && ready && sameLevel;
    }

    public static boolean protectsActor(boolean active, UUID owner, UUID griffith, UUID casca, UUID target) {
        return active && target != null && (target.equals(owner) || target.equals(griffith) || target.equals(casca));
    }

    public static boolean defuseCreeper(boolean explicitlyIgnited, boolean hasTarget,
                                       boolean removedProtectedTarget, boolean nearCamp) {
        return !explicitlyIgnited && !hasTarget && (removedProtectedTarget || nearCamp);
    }

    /** Includes the actual persisted foundation depth, not just the player's walking bounds. */
    public record Region(int bottom, int topExclusive) {
        public Region {
            if (bottom >= topExclusive) throw new IllegalArgumentException("Empty camp safety region");
        }
        public static Region at(int floor, int foundationBottom) {
            return new Region(Math.min(foundationBottom, Math.addExact(floor, WarCampPlan.MIN_Y_OFFSET)),
                Math.addExact(floor, WarCampPlan.MAX_Y_OFFSET + 1));
        }
        public boolean contains(double x, double y, double z) {
            return contains(x, y, z, 0);
        }
        public boolean withinMobReach(double x, double y, double z) {
            return contains(x, y, z, MOB_REACH_MARGIN);
        }
        private boolean contains(double x, double y, double z, int margin) {
            return x >= WarCampPlan.MIN_X - margin && x < WarCampPlan.MAX_X + 1 + margin
                && z >= WarCampPlan.MIN_Z - margin && z < WarCampPlan.MAX_Z + 1 + margin
                && y >= (long) bottom - margin && y < (long) topExclusive + margin;
        }
    }
}
