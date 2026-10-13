package dev.bekhilitt.campaign;

import dev.bekhilitt.combat.Duel;
import dev.bekhilitt.combat.EpicDuelAttempt;
import java.util.Objects;
import java.util.UUID;
import static dev.bekhilitt.campaign.CampaignSnapshot.*;

/**
 * Single writer of campaign progress. Call only from trusted server adapters.
 * A changed snapshot must be persisted before acknowledging durable completion;
 * this class makes no claim that a successful method call is a disk transaction.
 */
public final class CampaignService {
    public enum Result {
        APPLIED, ALREADY_APPLIED, WRONG_PARTICIPANT, STALE_ATTEMPT, STALE_REVISION,
        WRONG_STAGE, NOT_ACTIVE, TERMINAL, INCOMPLETE_DUEL, FOREIGN_DUEL;
        public boolean changed() { return this == APPLIED; }
    }

    private CampaignSnapshot snapshot;
    private Duel activeDuel;
    private EpicDuelAttempt activeEpicDuel;
    private CampaignService(CampaignSnapshot snapshot) { this.snapshot = Objects.requireNonNull(snapshot); }

    public static CampaignService create(UUID campaignId, UUID participantId) {
        return new CampaignService(CampaignSnapshot.at(campaignId, participantId, 0, Stage.INJURY,
                UUID.randomUUID(), UUID.randomUUID(), Status.ACTIVE, Interruption.NONE));
    }

    /**
     * Never resets invalid/future data. In-progress saves become paused checkpoints;
     * resuming creates a fresh attempt, with no remembered animation or duel state.
     * The adapter must persist snapshot(), which may now have a newer revision.
     */
    public static CampaignService restore(CampaignSnapshot saved) {
        CampaignService service = new CampaignService(saved);
        if (saved.status() == Status.ACTIVE) service.interrupt(saved.participantId(), saved.attemptId(), Interruption.SAVE_EXIT);
        return service;
    }

    public synchronized CampaignSnapshot snapshot() { return snapshot; }

    /** Expected stage and revision prevent duplicate input from advancing two dialogue nodes. */
    public synchronized Result advance(UUID participantId, UUID attemptId, long expectedRevision, Stage expectedStage) {
        Objects.requireNonNull(expectedStage, "expectedStage");
        Result identity = identity(participantId, attemptId);
        if (identity != null) return identity;
        if (expectedRevision < 0 || expectedRevision > snapshot.revision()) return Result.STALE_REVISION;
        if (expectedStage.ordinal() < snapshot.stage().ordinal() && expectedRevision < snapshot.revision())
            return Result.ALREADY_APPLIED;
        if (snapshot.complete()) return Result.TERMINAL;
        if (snapshot.status() != Status.ACTIVE) return Result.NOT_ACTIVE;
        if (expectedRevision != snapshot.revision()) return Result.STALE_REVISION;
        if (snapshot.stage() != expectedStage || expectedStage == Stage.DUEL) return Result.WRONG_STAGE;
        replace(Stage.values()[expectedStage.ordinal() + 1], Status.ACTIVE, Interruption.NONE, snapshot.attemptId());
        return Result.APPLIED;
    }

    /**
     * Only this duel instance can complete this attempt. A completed debug duel or
     * a stale duel from before interruption cannot grant canonical story effects.
     * Repeated calls for the same live attempt return the same runtime instance.
     */
    public synchronized Duel beginDuel(UUID participantId, UUID attemptId, long expectedRevision) {
        Result identity = identity(participantId, attemptId);
        if (identity != null || expectedRevision != snapshot.revision()
                || snapshot.status() != Status.ACTIVE || snapshot.stage() != Stage.DUEL)
            throw new IllegalStateException("Duel requires the current participant, attempt, revision, and active DUEL checkpoint");
        if (activeEpicDuel != null) throw new IllegalStateException("An Epic Fight encounter already owns this attempt");
        if (activeDuel == null) activeDuel = new Duel();
        return activeDuel;
    }

    /** Creates the runtime story boundary, bound to this exact server-owned opponent. */
    public synchronized EpicDuelAttempt beginEpicDuel(UUID participantId, UUID attemptId, long expectedRevision, UUID opponentId) {
        Result identity = identity(participantId, attemptId);
        if (identity != null || expectedRevision != snapshot.revision()
                || snapshot.status() != Status.ACTIVE || snapshot.stage() != Stage.DUEL)
            throw new IllegalStateException("Epic duel requires the current participant, attempt, revision, and active DUEL checkpoint");
        if (activeDuel != null) throw new IllegalStateException("A legacy duel already owns this attempt");
        if (activeEpicDuel == null) activeEpicDuel = new EpicDuelAttempt(participantId, attemptId, opponentId);
        else if (!activeEpicDuel.opponentId().equals(opponentId))
            throw new IllegalStateException("A different opponent cannot replace the active attempt");
        return activeEpicDuel;
    }

    /** Canonical story effects stay atomic and idempotent, independently of the combat engine. */
    public synchronized Result completeEpicDuel(UUID participantId, UUID attemptId, long expectedRevision, EpicDuelAttempt duel) {
        Result identity = identity(participantId, attemptId);
        if (identity != null) return identity;
        if (expectedRevision < 0 || expectedRevision > snapshot.revision()) return Result.STALE_REVISION;
        if (snapshot.complete()) return Result.ALREADY_APPLIED;
        if (snapshot.status() != Status.ACTIVE) return Result.NOT_ACTIVE;
        if (expectedRevision != snapshot.revision()) return Result.STALE_REVISION;
        if (snapshot.stage() != Stage.DUEL) return Result.WRONG_STAGE;
        if (duel == null || duel != activeEpicDuel) return Result.FOREIGN_DUEL;
        if (duel.phase() != EpicDuelAttempt.Phase.COMPLETE) return Result.INCOMPLETE_DUEL;
        replace(Stage.END, Status.COMPLETE, Interruption.NONE, snapshot.attemptId());
        activeEpicDuel = null;
        return Result.APPLIED;
    }

    /** Commits canonical defeat, joining, ledger, and END in one immutable revision. */
    public synchronized Result completeDuel(UUID participantId, UUID attemptId, long expectedRevision, Duel duel) {
        Result identity = identity(participantId, attemptId);
        if (identity != null) return identity;
        if (expectedRevision < 0 || expectedRevision > snapshot.revision()) return Result.STALE_REVISION;
        if (snapshot.complete()) return Result.ALREADY_APPLIED;
        if (snapshot.status() != Status.ACTIVE) return Result.NOT_ACTIVE;
        if (expectedRevision != snapshot.revision()) return Result.STALE_REVISION;
        if (snapshot.stage() != Stage.DUEL) return Result.WRONG_STAGE;
        if (duel == null || duel != activeDuel) return Result.FOREIGN_DUEL;
        if (duel.phase() != Duel.Phase.COMPLETE) return Result.INCOMPLETE_DUEL;
        replace(Stage.END, Status.COMPLETE, Interruption.NONE, snapshot.attemptId());
        activeDuel = null;
        return Result.APPLIED;
    }

    /** Technical interruption is never a defeat, a membership grant, or a new mission. */
    public synchronized Result interrupt(UUID participantId, UUID attemptId, Interruption reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == Interruption.NONE) throw new IllegalArgumentException("An interruption reason is required");
        Result identity = identity(participantId, attemptId);
        if (identity != null) return identity;
        if (snapshot.complete()) return Result.TERMINAL;
        if (snapshot.status() == Status.PAUSED) return Result.ALREADY_APPLIED;
        // Validate/build the replacement before touching runtime state (including revision overflow).
        replace(snapshot.stage(), Status.PAUSED, reason, snapshot.attemptId());
        if (activeDuel != null) activeDuel.abort();
        activeDuel = null;
        if (activeEpicDuel != null) activeEpicDuel.abort();
        activeEpicDuel = null;
        return Result.APPLIED;
    }

    /** Explicit server-authorized resume; completed episodes can never be replayed. */
    public synchronized Result resume(UUID participantId) {
        if (!snapshot.participantId().equals(participantId)) return Result.WRONG_PARTICIPANT;
        if (snapshot.complete()) return Result.TERMINAL;
        if (snapshot.status() == Status.ACTIVE) return Result.ALREADY_APPLIED;
        replace(snapshot.stage(), Status.ACTIVE, Interruption.NONE, UUID.randomUUID());
        return Result.APPLIED;
    }

    private Result identity(UUID participantId, UUID attemptId) {
        if (!snapshot.participantId().equals(participantId)) return Result.WRONG_PARTICIPANT;
        if (!snapshot.attemptId().equals(attemptId)) return Result.STALE_ATTEMPT;
        return null;
    }
    private void replace(Stage stage, Status status, Interruption interruption, UUID attemptId) {
        long nextRevision = Math.incrementExact(snapshot.revision());
        CampaignSnapshot next = CampaignSnapshot.at(snapshot.campaignId(), snapshot.participantId(),
                nextRevision, stage, snapshot.runId(), attemptId, status, interruption);
        snapshot = next;
    }
}
