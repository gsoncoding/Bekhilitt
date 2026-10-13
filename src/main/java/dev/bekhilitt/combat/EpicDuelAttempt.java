package dev.bekhilitt.combat;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-owned story boundary around a real Epic Fight encounter.
 *
 * This class does not deal damage, decide collisions, execute player inputs, or
 * simulate guard/dodge windows. Its counters accept only observed combat results
 * from the trusted platform adapter. Epic Fight owns all fighting mechanics.
 */
public final class EpicDuelAttempt {
    public enum Phase { PREPARATION, FIGHT, WARNING, FINISH, RESULT, COMPLETE, ABORTED }
    public enum FinishReason { TRAINING_OBSERVED, PLAYER_EXHAUSTED, OPPONENT_EXHAUSTED, TIME_LIMIT }
    public static final int PREPARATION_TICKS = 3 * 20;
    public static final int MIN_FIGHT_TICKS = 20 * 20;
    public static final int MAX_FIGHT_TICKS = 90 * 20;
    public static final int WARNING_TICKS = 60;
    public static final int FINISH_TICKS = 70;
    public static final int RESULT_TICKS = 90;

    private final UUID participantId;
    private final UUID attemptId;
    private final UUID opponentId;
    private Phase phase = Phase.PREPARATION;
    private FinishReason finishReason;
    private int ticks, fightTicks, phaseTick, landed, blocks, dodges, received, feedback;

    public EpicDuelAttempt(UUID participantId, UUID attemptId, UUID opponentId) {
        this.participantId = Objects.requireNonNull(participantId, "participantId");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
        this.opponentId = Objects.requireNonNull(opponentId, "opponentId");
        if (participantId.equals(opponentId)) throw new IllegalArgumentException("Opponent must be a different entity");
    }

    public UUID participantId() { return participantId; }
    public UUID attemptId() { return attemptId; }
    public UUID opponentId() { return opponentId; }
    public Phase phase() { return phase; }
    public FinishReason finishReason() { return finishReason; }
    public int ticks() { return ticks; }
    public int fightTicks() { return fightTicks; }
    public int phaseTick() { return phaseTick; }
    public int landed() { return landed; }
    public int blocks() { return blocks; }
    public int dodges() { return dodges; }
    public int received() { return received; }
    public boolean fighting() { return phase == Phase.FIGHT; }
    public boolean controlsLocked() { return phase == Phase.FINISH || phase == Phase.RESULT || phase == Phase.COMPLETE; }
    public boolean trainingObserved() { return landed >= 2 && blocks >= 1 && dodges >= 1; }

    /** Receives final positive damage, never a swing request or a hitbox guess. */
    public boolean observeDamage(UUID attacker, UUID victim, float actualDamage) {
        if (!fighting() || !Float.isFinite(actualDamage) || actualDamage <= 0) return false;
        if (participantId.equals(attacker) && opponentId.equals(victim)) {
            landed++;
            feedback = 1;
            return true;
        }
        if (opponentId.equals(attacker) && participantId.equals(victim)) {
            received++;
            feedback = 2;
            return true;
        }
        return false;
    }

    /** Receives Epic Fight's resolved guard result against this attempt's opponent. */
    public boolean observeGuard(UUID attacker, UUID defender) {
        if (!fighting() || !opponentId.equals(attacker) || !participantId.equals(defender)) return false;
        blocks++;
        feedback = 3;
        return true;
    }

    /** Receives Epic Fight's resolved dodge result, not merely a dodge animation. */
    public boolean observeDodge(UUID attacker, UUID defender) {
        if (!fighting() || !opponentId.equals(attacker) || !participantId.equals(defender)) return false;
        dodges++;
        feedback = 4;
        return true;
    }

    /** A near-knockout ends combat without killing either story participant. */
    public boolean exhausted(UUID entity) {
        if (!fighting()) return false;
        if (participantId.equals(entity)) return warn(FinishReason.PLAYER_EXHAUSTED);
        if (opponentId.equals(entity)) return warn(FinishReason.OPPONENT_EXHAUSTED);
        return false;
    }

    public int consumeFeedback() { int value = feedback; feedback = 0; return value; }

    /** Only the story coda is timed. The live fight has no scripted combat steps. */
    public void tick() {
        if (phase == Phase.COMPLETE || phase == Phase.ABORTED) return;
        ticks++;
        phaseTick++;
        switch (phase) {
            case PREPARATION -> { if (phaseTick >= PREPARATION_TICKS) change(Phase.FIGHT); }
            case FIGHT -> {
                fightTicks++;
                if (phaseTick >= MIN_FIGHT_TICKS && trainingObserved()) warn(FinishReason.TRAINING_OBSERVED);
                else if (phaseTick >= MAX_FIGHT_TICKS) warn(FinishReason.TIME_LIMIT);
            }
            case WARNING -> { if (phaseTick >= WARNING_TICKS) change(Phase.FINISH); }
            case FINISH -> { if (phaseTick >= FINISH_TICKS) change(Phase.RESULT); }
            case RESULT -> { if (phaseTick >= RESULT_TICKS) change(Phase.COMPLETE); }
            default -> { }
        }
    }

    public void abort() {
        if (phase != Phase.COMPLETE && phase != Phase.ABORTED) change(Phase.ABORTED);
    }

    private boolean warn(FinishReason reason) {
        finishReason = reason;
        change(Phase.WARNING);
        return true;
    }

    private void change(Phase next) { phase = next; phaseTick = 0; }
}
