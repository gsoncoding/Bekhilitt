package dev.bekhilitt.combat;

import dev.bekhilitt.campaign.CampaignService;
import dev.bekhilitt.campaign.CampaignSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillPracticeAttemptTest {
    private final UUID owner = UUID.randomUUID();
    private CampaignService completed() {
        var campaign = CampaignService.create(UUID.randomUUID(), owner);
        while (campaign.snapshot().stage() != CampaignSnapshot.Stage.DUEL) {
            var s = campaign.snapshot(); campaign.advance(owner, s.attemptId(), s.revision(), s.stage());
        }
        var s = campaign.snapshot();
        var duel = campaign.beginEpicDuel(owner, s.attemptId(), s.revision(), UUID.randomUUID());
        for (int i = 0; i < EpicDuelAttempt.PREPARATION_TICKS; i++) duel.tick();
        duel.exhausted(owner);
        while (duel.phase() != EpicDuelAttempt.Phase.COMPLETE) duel.tick();
        campaign.completeEpicDuel(owner, s.attemptId(), s.revision(), duel);
        return campaign;
    }
    @Test void practiceRequiresCompletedStoryAndExactOwner() {
        var fresh = CampaignService.create(UUID.randomUUID(), owner);
        assertThrows(IllegalArgumentException.class, () -> new SkillPracticeAttempt(fresh.snapshot(), owner, UUID.randomUUID()));
        var done = completed();
        assertThrows(IllegalArgumentException.class, () -> new SkillPracticeAttempt(done.snapshot(), UUID.randomUUID(), UUID.randomUUID()));
    }
    @Test void observationsAreOwnedIdempotentAndDoNotMutateCampaign() {
        var campaign = completed(); var original = campaign.snapshot();
        var practice = new SkillPracticeAttempt(original, owner, UUID.randomUUID());
        assertFalse(practice.observeLeap(UUID.randomUUID()));
        assertFalse(practice.observeSlam(UUID.randomUUID()));
        assertTrue(practice.observeLeap(owner));
        assertFalse(practice.observeLeap(owner));
        assertTrue(practice.observeSlam(owner));
        assertFalse(practice.observeSlam(owner));
        assertTrue(practice.allObserved());
        practice.tick(); practice.stop();
        assertSame(original, campaign.snapshot());
        assertEquals(original.appliedEffects(), campaign.snapshot().appliedEffects());
    }
    @Test void practiceExpiresAtFiveMinutesWithoutInventingObservedSkills() {
        var practice = new SkillPracticeAttempt(completed().snapshot(), owner, UUID.randomUUID());
        for (int i = 0; i < SkillPracticeAttempt.MAX_TICKS - 1; i++) practice.tick();
        assertTrue(practice.active()); practice.tick(); assertFalse(practice.active());
        assertFalse(practice.leapObserved()); assertFalse(practice.slamObserved());
        assertFalse(practice.observeLeap(owner)); assertFalse(practice.observeSlam(owner));
        practice.tick(); assertEquals(SkillPracticeAttempt.MAX_TICKS, practice.ticks());
    }
    @Test void stoppedPracticeCannotResumeFromLateNativeEvents() {
        var practice = new SkillPracticeAttempt(completed().snapshot(), owner, UUID.randomUUID());
        practice.tick(); practice.stop(); practice.tick();
        assertEquals(1, practice.ticks()); assertFalse(practice.active());
        assertFalse(practice.observeLeap(owner)); assertFalse(practice.observeSlam(owner));
    }
}
