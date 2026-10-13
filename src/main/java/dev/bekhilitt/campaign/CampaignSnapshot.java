package dev.bekhilitt.campaign;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One immutable server-owned snapshot for the first episode. The checkpoint is an
 * entry node, never a frame in an animation or a partly completed combat attempt.
 * No inventory, experience, client input, entity IDs, or future mission is stored.
 */
public record CampaignSnapshot(
        int schemaVersion, String contentVersion, UUID campaignId, UUID participantId,
        long revision, String chapterId, Stage checkpoint, UUID runId, UUID attemptId,
        Status status, Interruption interruption, Set<String> completedEventIds,
        Set<String> flags, Set<String> appliedEffects) {
    public static final int SCHEMA_VERSION = 1;
    public static final String CONTENT_VERSION = "first_episode_1";
    public static final String CHAPTER_ID = "bekhilitt:golden_age";
    public static final String FIRST_DUEL_DONE = "bekhilitt:first_duel_done";
    public static final String JOINED_HAWKS = "bekhilitt:joined_hawks";
    public static final String EPISODE_COMPLETE = "bekhilitt:first_episode_complete";
    private static final UUID ZERO_UUID = new UUID(0, 0);

    public enum Status { ACTIVE, PAUSED, COMPLETE }
    public enum Interruption {
        NONE, SAVE_EXIT, DISCONNECT, PLAYER_DEATH, NPC_UNAVAILABLE,
        REGION_UNAVAILABLE, DIALOGUE_CLOSED, CANCELLED, PLATFORM_FAILURE
    }

    /** Compiled, fixed sequence for this content version; not an extensible dialogue parser. */
    public enum Stage {
        INJURY("injury", "bekhilitt:golden_age/meeting", "injury_shown", true),
        TRANSPORT("transport", "bekhilitt:golden_age/meeting", "transport_shown", true),
        RECOVERY("recovery", "bekhilitt:golden_age/recovery", "recovery_shown", true),
        AWAKENING("awakening", "bekhilitt:golden_age/recovery", "awakening_shown", false),
        CASCA_DIALOGUE("casca_dialogue", "bekhilitt:golden_age/recovery", "casca_dialogue_done", false),
        GRIFFITH_DIALOGUE("griffith_dialogue", "bekhilitt:golden_age/first_duel", "griffith_dialogue_done", false),
        DUEL("duel", "bekhilitt:golden_age/first_duel", "first_duel_done", false),
        END("end", "bekhilitt:golden_age/first_duel", "episode_complete", false);

        private final String nodeId, sceneId, effectId;
        private final boolean controlsLocked;
        Stage(String nodeId, String sceneId, String effectId, boolean controlsLocked) {
            this.nodeId = nodeId;
            this.sceneId = sceneId;
            this.effectId = effectId;
            this.controlsLocked = controlsLocked;
        }
        public String nodeId() { return nodeId; }
        public String sceneId() { return sceneId; }
        public String effectId() { return effectId; }
        public boolean controlsLocked() { return controlsLocked; }
        public boolean dialogue() { return this == CASCA_DIALOGUE || this == GRIFFITH_DIALOGUE; }
    }

    public CampaignSnapshot {
        if (schemaVersion != SCHEMA_VERSION)
            throw invalid("schemaVersion", "unsupported version " + schemaVersion);
        if (!CONTENT_VERSION.equals(contentVersion))
            throw invalid("contentVersion", "unsupported content " + contentVersion);
        requireUuid(campaignId, "campaignId");
        requireUuid(participantId, "participantId");
        requireUuid(runId, "runId");
        requireUuid(attemptId, "attemptId");
        if (!CHAPTER_ID.equals(chapterId)) throw invalid("chapterId", "unknown chapter");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(interruption, "interruption");
        if (revision < checkpoint.ordinal() + (status == Status.PAUSED ? 1L : 0L))
            throw invalid("revision", "negative or earlier than the checkpoint");
        if ((checkpoint == Stage.END) != (status == Status.COMPLETE))
            throw invalid("status", "END and COMPLETE must be committed together");
        if ((status == Status.PAUSED) != (interruption != Interruption.NONE))
            throw invalid("interruption", "a reason is required only for PAUSED");
        completedEventIds = Set.copyOf(Objects.requireNonNull(completedEventIds, "completedEventIds"));
        flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
        appliedEffects = Set.copyOf(Objects.requireNonNull(appliedEffects, "appliedEffects"));
        if (!eventsFor(checkpoint).equals(completedEventIds))
            throw invalid("completedEventIds", "events do not match the canonical checkpoint");
        if (!flagsFor(checkpoint).equals(flags))
            throw invalid("flags", "completion and membership must be atomic; unknown flags forbidden");
        if (!effectsFor(campaignId, checkpoint).equals(appliedEffects))
            throw invalid("appliedEffects", "missing, unknown, foreign, or premature effect");
    }

    public Stage stage() { return checkpoint; }
    public String sceneId() { return checkpoint.sceneId(); }
    public String checkpointNodeId() { return checkpoint.nodeId(); }
    public boolean complete() { return status == Status.COMPLETE; }
    public boolean joinedHawks() { return flags.contains(JOINED_HAWKS); }
    public boolean controlsLocked() { return status == Status.ACTIVE && checkpoint.controlsLocked(); }

    /** The permanent effect key intentionally excludes runId and attemptId. */
    public static String effectKey(UUID campaignId, String sceneId, String effectId) {
        return campaignId + "|" + sceneId + "|" + effectId;
    }

    static CampaignSnapshot at(UUID campaignId, UUID participantId, long revision, Stage stage,
                               UUID runId, UUID attemptId, Status status, Interruption interruption) {
        return new CampaignSnapshot(SCHEMA_VERSION, CONTENT_VERSION, campaignId, participantId,
                revision, CHAPTER_ID, stage, runId, attemptId, status, interruption,
                eventsFor(stage), flagsFor(stage), effectsFor(campaignId, stage));
    }

    private static Set<String> eventsFor(Stage stage) {
        Set<String> events = new LinkedHashSet<>();
        if (stage.ordinal() >= Stage.TRANSPORT.ordinal()) {
            events.add("bekhilitt:encounter_hawks");
            events.add("bekhilitt:initial_clash");
        }
        if (stage.ordinal() >= Stage.DUEL.ordinal()) events.add("bekhilitt:recovery_and_challenge");
        if (stage == Stage.END) events.add("bekhilitt:joining_duel_resolved");
        return Set.copyOf(events);
    }
    private static Set<String> flagsFor(Stage stage) {
        return stage == Stage.END ? Set.of(FIRST_DUEL_DONE, JOINED_HAWKS, EPISODE_COMPLETE) : Set.of();
    }
    private static Set<String> effectsFor(UUID campaignId, Stage stage) {
        Set<String> effects = new LinkedHashSet<>();
        for (Stage previous : Stage.values()) {
            if (previous.ordinal() >= stage.ordinal()) break;
            effects.add(effectKey(campaignId, previous.sceneId(), previous.effectId()));
        }
        if (stage == Stage.END) {
            effects.add(effectKey(campaignId, Stage.END.sceneId(), "joined_hawks"));
            effects.add(effectKey(campaignId, Stage.END.sceneId(), Stage.END.effectId()));
        }
        return Set.copyOf(effects);
    }
    static void requireUuid(UUID value, String field) {
        if (value == null || ZERO_UUID.equals(value)) throw invalid(field, "nonzero UUID required");
    }
    static IllegalArgumentException invalid(String field, String reason) {
        return new IllegalArgumentException("Campaign snapshot " + field + ": " + reason
                + ". Existing data must be preserved; no automatic reset is permitted.");
    }
}
