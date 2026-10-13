package dev.bekhilitt.platform;

import dev.bekhilitt.campaign.CampaignSnapshot.Stage;
import dev.bekhilitt.protocol.EpisodeAdvancePayload.Action;
import org.junit.jupiter.api.Test;

import static dev.bekhilitt.platform.EpisodeAdvanceRouting.Route.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeAdvanceRoutingTest {
    private static final long REVISION = 42;

    @Test void currentCancelStopsEveryStoryStage() {
        for (Stage stage : Stage.values())
            assertEquals(STOP, route(REVISION, Action.CANCEL, false, stage, false), stage.name());
    }

    @Test void cancelWinsOverEndToCampTransition() {
        assertEquals(STOP, route(REVISION, Action.CANCEL, false, Stage.END, false));
    }

    @Test void cancelWinsOverCampVisitAndTalkDismissal() {
        // Both walking and an open camp talk have a visit; neither may swallow a genuine CANCEL.
        assertEquals(STOP, route(REVISION, Action.CANCEL, true, Stage.END, false));
    }

    @Test void cancelStillStopsNativePractice() {
        assertEquals(STOP, route(REVISION, Action.CANCEL, false, Stage.END, true));
    }

    @Test void staleAndFutureIntentsCannotActInAnyMode() {
        for (long intentRevision : new long[] {0, REVISION - 1, REVISION + 1, Long.MAX_VALUE})
            for (Action action : Action.values())
                for (Stage stage : Stage.values()) {
                    assertEquals(REFRESH, route(intentRevision, action, false, stage, false));
                    assertEquals(REFRESH, route(intentRevision, action, true, stage, false));
                    assertEquals(REFRESH, route(intentRevision, action, false, stage, true));
                }
    }

    @Test void nextDismissesEndIntoFreeCamp() {
        assertEquals(ENTER_CAMP, route(REVISION, Action.NEXT, false, Stage.END, false));
    }

    @Test void nextDismissesCampTalkWithoutStoppingVisitOrEnteringCampAgain() {
        assertEquals(CLOSE_CAMP_TALK, route(REVISION, Action.NEXT, true, Stage.END, false));
    }

    @Test void nextCannotReenterCampFromNativePractice() {
        assertEquals(REFRESH, route(REVISION, Action.NEXT, false, Stage.END, true));
    }

    @Test void ordinaryNextRetainsServerDialogueAndProximityChecks() {
        for (Stage stage : Stage.values())
            if (stage != Stage.END)
                assertEquals(DIALOGUE, route(REVISION, Action.NEXT, false, stage, false), stage.name());
    }

    @Test void cancelRetriedAfterDuelToEndRaceStopsAtTheNewRevision() {
        long duelRevision = 40, endRevision = 41, refreshedRevision = 42;
        assertEquals(REFRESH, EpisodeAdvanceRouting.route(endRevision, duelRevision,
            Action.CANCEL, false, Stage.END, false));
        assertEquals(STOP, EpisodeAdvanceRouting.route(refreshedRevision, refreshedRevision,
            Action.CANCEL, false, Stage.END, false));
    }

    @Test void pendingCancellationAlsoTerminatesAnAlreadyEnteredCamp() {
        assertEquals(REFRESH, EpisodeAdvanceRouting.route(43, REVISION,
            Action.CANCEL, true, Stage.END, false));
        assertEquals(STOP, EpisodeAdvanceRouting.route(44, 44,
            Action.CANCEL, true, Stage.END, false));
    }

    @Test void replayedNextCannotDismissANewerCampPresentation() {
        assertEquals(ENTER_CAMP, route(REVISION, Action.NEXT, false, Stage.END, false));
        assertEquals(REFRESH, EpisodeAdvanceRouting.route(REVISION + 1, REVISION,
            Action.NEXT, true, Stage.END, false));
    }

    private static EpisodeAdvanceRouting.Route route(long intentRevision, Action action,
                                                     boolean campVisit, Stage stage, boolean practice) {
        return EpisodeAdvanceRouting.route(REVISION, intentRevision, action, campVisit, stage, practice);
    }
}
