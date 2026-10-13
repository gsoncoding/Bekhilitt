package dev.bekhilitt.campaign;

import java.util.Objects;
import java.util.UUID;

/** A repeatable visit reads COMPLETE; it has no authority to alter story, rewards or duel state. */
public final class CampVisit {
    public enum Speaker { CASCA, GRIFFITH }
    private Speaker speaker;
    private int cooldown;
    public CampVisit(CampaignSnapshot completed, UUID owner) {
        requireCompletedOwner(completed, owner);
    }
    public static void requireCompletedOwner(CampaignSnapshot completed, UUID owner) {
        if (completed == null || !completed.complete() || !completed.participantId().equals(owner))
            throw new IllegalStateException("Лагерь доступен владельцу после завершения первого эпизода.");
    }
    public Speaker speaker() { return speaker; }
    public boolean open(Speaker target, double distance, boolean lineOfSight) {
        Objects.requireNonNull(target);
        if (speaker != null || cooldown > 0 || !Double.isFinite(distance) || distance < 0 || distance > 4 || !lineOfSight) return false;
        speaker = target; return true;
    }
    public void close() { speaker = null; cooldown = 10; }
    public void tick() { if (cooldown > 0) cooldown--; }
}
