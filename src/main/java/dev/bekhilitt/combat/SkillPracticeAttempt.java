package dev.bekhilitt.combat;

import dev.bekhilitt.campaign.CampaignSnapshot;
import java.util.Objects;
import java.util.UUID;

/** Observes a separate, bounded native-skill practice. It has no campaign writer. */
public final class SkillPracticeAttempt {
    public static final int MAX_TICKS = 5 * 60 * 20;
    private final UUID participantId, attemptId;
    private int ticks;
    private boolean leap, slam, stopped;

    public SkillPracticeAttempt(CampaignSnapshot completedCampaign, UUID participantId, UUID attemptId) {
        Objects.requireNonNull(completedCampaign);
        this.participantId = Objects.requireNonNull(participantId);
        this.attemptId = Objects.requireNonNull(attemptId);
        if (!completedCampaign.complete() || !completedCampaign.participantId().equals(participantId))
            throw new IllegalArgumentException("Practice requires the owner of a completed episode");
    }
    public UUID participantId() { return participantId; }
    public UUID attemptId() { return attemptId; }
    public int ticks() { return ticks; }
    public boolean active() { return !stopped && ticks < MAX_TICKS; }
    public boolean leapObserved() { return leap; }
    public boolean slamObserved() { return slam; }
    public boolean allObserved() { return leap && slam; }
    public boolean observeLeap(UUID owner) {
        if (!active() || !participantId.equals(owner) || leap) return false;
        leap = true; return true;
    }
    public boolean observeSlam(UUID owner) {
        if (!active() || !participantId.equals(owner) || slam) return false;
        slam = true; return true;
    }
    public void tick() { if (active()) ticks++; }
    public void stop() { stopped = true; }
}
