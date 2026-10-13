package dev.bekhilitt.platform;

import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native event contracts can be tested without constructing or starting a world. */
class CampSafetyEventsTest {
    @Test void campLeaseDeniesPickupEvenIfAnotherHookAllowedIt() {
        var event = new ItemEntityPickupEvent.Pre(null, null);
        event.setCanPickup(TriState.TRUE);
        CampSafetyEvents.denyPickup(event, true);
        assertEquals(TriState.FALSE, event.canPickup());
    }
    @Test void recoveryLeaseAlsoDeniesPickupWithoutLiveSession() {
        var event = new ItemEntityPickupEvent.Pre(null, null);
        CampSafetyEvents.denyPickup(event, true);
        assertEquals(TriState.FALSE, event.canPickup());
    }
    @Test void noLeasePreservesNativePickupPolicy() {
        var event = new ItemEntityPickupEvent.Pre(null, null);
        CampSafetyEvents.denyPickup(event, false);
        assertEquals(TriState.DEFAULT, event.canPickup());
        event.setCanPickup(TriState.TRUE);
        CampSafetyEvents.denyPickup(event, false);
        assertEquals(TriState.TRUE, event.canPickup());
    }
    @Test void campXpPickupCannotGrantXpOrRepairItems() {
        var event = new PlayerXpEvent.PickupXp(null, null);
        CampSafetyEvents.denyXpPickup(event, true);
        assertTrue(event.isCanceled());
    }
    @Test void noLeasePreservesXpAndOtherModsDenials() {
        var event = new PlayerXpEvent.PickupXp(null, null);
        CampSafetyEvents.denyXpPickup(event, false);
        assertFalse(event.isCanceled());
        event.setCanceled(true);
        CampSafetyEvents.denyXpPickup(event, false);
        assertTrue(event.isCanceled());
    }
    @Test void targetGuardDoesNotCancelAndPreserveOldTarget() {
        for (var type : LivingChangeTargetEvent.LivingTargetType.values()) {
            var event = new LivingChangeTargetEvent(null, null, type);
            CampSafetyEvents.denyTarget(event, true);
            assertNull(event.getNewAboutToBeSetTarget());
            assertFalse(event.isCanceled());
        }
    }
    @Test void absentSessionNeverActivatesRegionOrTargetGuard() {
        var episode = new FirstEpisode();
        assertNull(episode.activeCampSafety(null));
        assertFalse(episode.protectsCampActor(null));
    }
}
