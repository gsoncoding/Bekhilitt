package dev.bekhilitt.combat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PracticeRecoveryTest {
    @Test void unresolvedMarkerRefusesAllStoryStartsBeforeTheirRestoreOrCreatePath() {
        // Presence, not lease validity/campaign existence, controls this gate.
        assertThrows(IllegalStateException.class, () -> PracticeRecovery.requireStoryStartAllowed(true));
        assertDoesNotThrow(() -> PracticeRecovery.requireStoryStartAllowed(false));
    }

    @Test void badCampaignCannotReleaseProtectionOrGuessReturnState() {
        var mutations = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> PracticeRecovery.restore(false,
            () -> mutations.add("position"), () -> mutations.add("release marker")));
        assertTrue(mutations.isEmpty());
    }

    @Test void originalPositionIsVerifiedBeforeProtectionIsReleased() throws IOException {
        var mutations = new ArrayList<String>();
        PracticeRecovery.restore(true, () -> mutations.add("verified return"), () -> mutations.add("release marker"));
        assertEquals(List.of("verified return", "release marker"), mutations);
    }

    @Test void failedReturnSaveRetainsProtection() {
        var mutations = new ArrayList<String>();
        assertThrows(IOException.class, () -> PracticeRecovery.restore(true,
            () -> { throw new IOException("owner save mismatch"); }, () -> mutations.add("release marker")));
        assertTrue(mutations.isEmpty());
    }

    @Test void missingDimensionRetainsProtection() {
        var mutations = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> PracticeRecovery.restore(true,
            () -> { throw new IllegalStateException("dimension unavailable"); }, () -> mutations.add("release marker")));
        assertTrue(mutations.isEmpty());
    }

    @Test void loadoutFailureDoesNotUndoVerifiedReturn() {
        var mutations = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> PracticeRecovery.restore(true,
            () -> mutations.add("verified return"), () -> { throw new IllegalArgumentException("malformed lease"); }));
        assertEquals(List.of("verified return"), mutations);
    }
}
