package dev.bekhilitt.campaign;

import dev.bekhilitt.combat.EpicDuelAttempt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.bekhilitt.campaign.CampaignSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class EpicCampaignTest {
    private final UUID player = UUID.randomUUID(), opponent = UUID.randomUUID();

    private CampaignService atDuel() {
        var campaign = CampaignService.create(UUID.randomUUID(), player);
        while (campaign.snapshot().stage() != Stage.DUEL) {
            var s = campaign.snapshot();
            assertEquals(CampaignService.Result.APPLIED, campaign.advance(player, s.attemptId(), s.revision(), s.stage()));
        }
        return campaign;
    }
    private EpicDuelAttempt begin(CampaignService campaign) {
        var s = campaign.snapshot();
        return campaign.beginEpicDuel(player, s.attemptId(), s.revision(), opponent);
    }
    private static void finish(EpicDuelAttempt duel) {
        for (int i = 0; i < EpicDuelAttempt.PREPARATION_TICKS; i++) duel.tick();
        duel.exhausted(duel.participantId());
        for (int i = 0; i < EpicDuelAttempt.WARNING_TICKS + EpicDuelAttempt.FINISH_TICKS + EpicDuelAttempt.RESULT_TICKS; i++) duel.tick();
    }

    @Test void runtimeEngineOwnsExactlyOneEncounter() {
        var campaign = atDuel(); var duel = begin(campaign); var s = campaign.snapshot();
        assertSame(duel, begin(campaign));
        assertThrows(IllegalStateException.class, () -> campaign.beginEpicDuel(player, s.attemptId(), s.revision(), UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> campaign.beginDuel(player, s.attemptId(), s.revision()));
    }

    @Test void legacyAndRuntimeEncountersCannotBothOwnAttempt() {
        var campaign = atDuel(); var s = campaign.snapshot();
        campaign.beginDuel(player, s.attemptId(), s.revision());
        assertThrows(IllegalStateException.class, () -> begin(campaign));
    }

    @Test void canonicalCompletionIsIdempotentAndDurable() {
        var campaign = atDuel(); var duel = begin(campaign); var before = campaign.snapshot();
        assertEquals(CampaignService.Result.INCOMPLETE_DUEL, campaign.completeEpicDuel(player, before.attemptId(), before.revision(), duel));
        finish(duel);
        assertEquals(CampaignService.Result.APPLIED, campaign.completeEpicDuel(player, before.attemptId(), before.revision(), duel));
        var completed = campaign.snapshot();
        assertTrue(completed.complete());
        assertEquals(Stage.END, completed.stage());
        assertEquals(before.revision() + 1, completed.revision());
        assertEquals(CampaignService.Result.ALREADY_APPLIED, campaign.completeEpicDuel(player, before.attemptId(), before.revision(), duel));
        assertEquals(completed, campaign.snapshot());
        assertEquals(completed, CampaignService.restore(completed).snapshot());
    }

    @Test void forgedAndForeignDuelCannotGrantProgress() {
        var campaign = atDuel(); var owned = begin(campaign); var s = campaign.snapshot();
        var foreign = new EpicDuelAttempt(player, s.attemptId(), opponent); finish(foreign);
        assertEquals(CampaignService.Result.FOREIGN_DUEL, campaign.completeEpicDuel(player, s.attemptId(), s.revision(), foreign));
        assertEquals(CampaignService.Result.WRONG_PARTICIPANT, campaign.completeEpicDuel(UUID.randomUUID(), s.attemptId(), s.revision(), owned));
        assertEquals(CampaignService.Result.STALE_ATTEMPT, campaign.completeEpicDuel(player, UUID.randomUUID(), s.revision(), owned));
        assertEquals(CampaignService.Result.STALE_REVISION, campaign.completeEpicDuel(player, s.attemptId(), s.revision() + 1, owned));
        assertEquals(s, campaign.snapshot());
    }

    @Test void interruptedAttemptAbortsAndNewAttemptRejectsOldObject() {
        var campaign = atDuel(); var old = begin(campaign); var s = campaign.snapshot();
        campaign.interrupt(player, s.attemptId(), Interruption.DISCONNECT);
        assertEquals(EpicDuelAttempt.Phase.ABORTED, old.phase());
        campaign.resume(player);
        var fresh = begin(campaign); var resumed = campaign.snapshot();
        assertNotSame(old, fresh);
        assertNotEquals(old.attemptId(), fresh.attemptId());
        assertEquals(CampaignService.Result.STALE_ATTEMPT, campaign.completeEpicDuel(player, s.attemptId(), s.revision(), old));
        assertEquals(CampaignService.Result.FOREIGN_DUEL, campaign.completeEpicDuel(player, resumed.attemptId(), resumed.revision(), old));
        assertFalse(campaign.snapshot().complete());
    }

    @Test void noEpisodeStageCanBeSkippedThroughNativeDuelApi() {
        var campaign = CampaignService.create(UUID.randomUUID(), player); var s = campaign.snapshot();
        assertThrows(IllegalStateException.class, () -> campaign.beginEpicDuel(player, s.attemptId(), s.revision(), opponent));
        var forged = new EpicDuelAttempt(player, s.attemptId(), opponent); finish(forged);
        assertEquals(CampaignService.Result.WRONG_STAGE, campaign.completeEpicDuel(player, s.attemptId(), s.revision(), forged));
    }
}
