package dev.bekhilitt.platform.story;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CampSafetyRulesTest {
    private final UUID owner = UUID.randomUUID(), griffith = UUID.randomUUID(), casca = UUID.randomUUID();

    @Test void onlyReadyVisitInSameLevelIsActive() {
        assertTrue(CampSafetyRules.active(true, true, true, true));
        assertFalse(CampSafetyRules.active(false, true, true, true));
        assertFalse(CampSafetyRules.active(true, false, true, true));
        assertFalse(CampSafetyRules.active(true, true, false, true));
        assertFalse(CampSafetyRules.active(true, true, true, false));
    }
    @Test void stopAndPracticeImmediatelyReleaseProtection() {
        assertTrue(CampSafetyRules.active(true, true, true, true));
        assertFalse(CampSafetyRules.active(false, false, false, true)); // stop/disconnect
        assertFalse(CampSafetyRules.active(true, false, true, true)); // practice/story/duel
        assertFalse(CampSafetyRules.protectsActor(false, owner, griffith, casca, owner));
    }
    @Test void onlyVisitorAndCurrentActorsAreProtected() {
        for (UUID id : new UUID[]{owner, griffith, casca})
            assertTrue(CampSafetyRules.protectsActor(true, owner, griffith, casca, id));
        assertFalse(CampSafetyRules.protectsActor(true, owner, griffith, casca, UUID.randomUUID()));
        assertFalse(CampSafetyRules.protectsActor(true, owner, griffith, casca, null));
        assertFalse(CampSafetyRules.protectsActor(true, owner, null, null, casca));
    }
    @Test void cachedCreeperTargetIsClearedBeyondBlockGriefMargin() {
        assertTrue(CampSafetyRules.defuseCreeper(false, false, true, false));
        assertTrue(CampSafetyRules.defuseCreeper(false, false, false, true));
        assertFalse(CampSafetyRules.defuseCreeper(false, false, false, false));
    }
    @Test void creeperGuardPreservesExplicitIgnitionAndUnrelatedTargets() {
        assertFalse(CampSafetyRules.defuseCreeper(true, false, true, true));
        assertFalse(CampSafetyRules.defuseCreeper(false, true, false, true));
    }
    @Test void exteriorBlastsCannotReachBoundaryBlocks() {
        var region = CampSafetyRules.Region.at(100, 81);
        for (int x : new int[]{-28, 28}) for (int z : new int[]{-28, 28}) {
            assertTrue(region.contains(x, 81, z));
            assertTrue(region.contains(x, 112, z));
        }
        assertFalse(region.contains(-29, 100, 0));
        assertFalse(region.contains(29, 100, 0));
        assertFalse(region.contains(0, 100, -29));
        assertFalse(region.contains(0, 100, 29));
        assertFalse(region.contains(0, 80, 0));
        assertFalse(region.contains(0, 113, 0));
    }
    @Test void retainsOnlyOutsideExplosionPositions() {
        var region = CampSafetyRules.Region.at(100, 81);
        var positions = new ArrayList<>(java.util.List.of(new WarCampPlan.Cell(0, 100, 0),
            new WarCampPlan.Cell(28, 112, 28), new WarCampPlan.Cell(29, 100, 0), new WarCampPlan.Cell(0, 80, 0)));
        positions.removeIf(p -> region.contains(p.x(), p.y(), p.z()));
        assertEquals(java.util.List.of(new WarCampPlan.Cell(29, 100, 0), new WarCampPlan.Cell(0, 80, 0)), positions);
    }
    @Test void foundationUsesActualDepthRatherThanWalkingFloor() {
        var region = CampSafetyRules.Region.at(100, 63);
        assertEquals(63, region.bottom());
        assertTrue(region.contains(0, 63, 0));
        assertTrue(region.contains(0, 95, 0));
        assertFalse(region.contains(0, 62, 0));
        assertEquals(95, CampSafetyRules.Region.at(100, 99).bottom());
    }
    @Test void protectsEveryAuthoredAndFoundationBlockAcrossSeeds() {
        for (long seed : new long[]{0, 1, 8175609, Long.MIN_VALUE}) {
            var layout = WarCampPlan.layout(seed);
            var ground = new LinkedHashMap<WarCampFoundation.Column, Integer>();
            layout.keySet().forEach(c -> ground.put(new WarCampFoundation.Column(c.x(), c.z()), 70 + Math.floorMod(c.x(), 10)));
            var foundation = WarCampFoundation.plan(layout, 100, ground);
            var region = CampSafetyRules.Region.at(100, ground.values().stream().mapToInt(Integer::intValue).min().orElseThrow());
            layout.keySet().forEach(c -> assertTrue(region.contains(c.x(), 100 + c.y(), c.z())));
            foundation.keySet().forEach(c -> assertTrue(region.contains(c.x(), c.y(), c.z())));
        }
    }
    @Test void mobReachBufferClosesEndermanBorderSampling() {
        var region = CampSafetyRules.Region.at(100, 81);
        assertTrue(region.withinMobReach(-30, 80, 0));
        assertTrue(region.withinMobReach(30, 114, 0));
        assertTrue(region.withinMobReach(0, 80, -30));
        assertTrue(region.withinMobReach(0, 114, 30));
        assertFalse(region.withinMobReach(-33, 100, 0));
        assertFalse(region.withinMobReach(33, 100, 0));
        assertFalse(region.withinMobReach(0, 76, 0));
        assertFalse(region.withinMobReach(0, 117, 0));
    }
    @Test void invalidCoordinatesAndOverflowFailClosed() {
        var region = CampSafetyRules.Region.at(100, 81);
        assertFalse(region.contains(Double.NaN, 100, 0));
        assertFalse(region.contains(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new CampSafetyRules.Region(5, 5));
        assertThrows(ArithmeticException.class, () -> CampSafetyRules.Region.at(Integer.MAX_VALUE, 0));
    }
}
