package dev.bekhilitt.campaign;

import dev.bekhilitt.combat.Duel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import static dev.bekhilitt.campaign.CampaignSnapshot.*;
import static dev.bekhilitt.campaign.CampaignService.Result;

/** Real dependency-free regression runner; the same cases are also JUnit tests. */
public final class CampaignSelfTest {
    private CampaignSelfTest() { }
    public static void main(String[] args) { runAll(); }
    public static void runAll() {
        var cases = new java.util.LinkedHashMap<String, Runnable>();
        cases.put("canonical ordered episode and atomic ending", CampaignSelfTest::canonicalEpisode);
        cases.put("mandatory bound Duel cannot be skipped", CampaignSelfTest::mandatoryDuel);
        cases.put("completed episode cannot be replayed", CampaignSelfTest::terminalCompletion);
        cases.put("revision and expected stage reject duplicate advancement", CampaignSelfTest::staleRequests);
        cases.put("participant and attempt ownership", CampaignSelfTest::ownership);
        cases.put("every interruption resumes each unfinished checkpoint", CampaignSelfTest::interruptionMatrix);
        cases.put("old Duel cannot finish a resumed attempt", CampaignSelfTest::interruptedDuel);
        cases.put("debug Duel cannot grant canonical completion", CampaignSelfTest::foreignDuel);
        cases.put("codec round trip at every checkpoint and status", CampaignSelfTest::codecRoundTrips);
        cases.put("malformed and ambiguous codec input is rejected", CampaignSelfTest::malformedCodec);
        cases.put("future and unknown versions are preserved by rejection", CampaignSelfTest::unsupportedVersions);
        cases.put("completion flags and event ledger are inseparable", CampaignSelfTest::corruptLedger);
        cases.put("snapshot and encoded maps are immutable", CampaignSelfTest::immutability);
        cases.put("effect keys exclude run and attempt identities", CampaignSelfTest::stableEffectKeys);
        cases.put("crash before completion restores only a checkpoint", CampaignSelfTest::crashBeforeCommit);
        cases.put("crash after completion never reopens combat", CampaignSelfTest::crashAfterCommit);
        cases.put("duplicate interrupt and resume are no-ops", CampaignSelfTest::idempotentRecovery);
        cases.put("revision overflow cannot partially mutate state", CampaignSelfTest::revisionOverflow);
        cases.put("deterministic codec output", CampaignSelfTest::deterministicCodec);
        cases.put("all snapshot identifiers are validated", CampaignSelfTest::identifiers);
        cases.put("no controls leased while paused or complete", CampaignSelfTest::controlLease);
        cases.put("deterministic adversarial event stream", CampaignSelfTest::adversarialEvents);
        cases.forEach((name, test) -> { test.run(); System.out.println("PASS " + name); });
        System.out.println("Campaign self-test: " + cases.size() + " passed, 0 failed");
    }

    static void canonicalEpisode() {
        CampaignService service = fresh();
        equal(Stage.INJURY, service.snapshot().stage());
        equal(0L, service.snapshot().revision());
        for (Stage expected : Stage.values()) {
            CampaignSnapshot state = service.snapshot();
            equal(expected, state.stage());
            check(!state.completedEventIds().contains("bekhilitt:first_raid"), "future event leaked");
            check(!state.flags().contains("bekhilitt:camp_intro_available"), "future quest leaked");
            if (expected == Stage.END) break;
            if (expected == Stage.DUEL) {
                equal(Set.of(), state.flags());
                Duel duel = begin(service);
                finish(duel);
                equal(Result.APPLIED, service.completeDuel(state.participantId(), state.attemptId(), state.revision(), duel));
            } else equal(Result.APPLIED, advance(service));
        }
        CampaignSnapshot end = service.snapshot();
        equal(7L, end.revision());
        equal(Status.COMPLETE, end.status());
        equal(Set.of(FIRST_DUEL_DONE, JOINED_HAWKS, EPISODE_COMPLETE), end.flags());
        equal(9, end.appliedEffects().size());
        equal(Set.of("bekhilitt:encounter_hawks", "bekhilitt:initial_clash", "bekhilitt:recovery_and_challenge",
                "bekhilitt:joining_duel_resolved"), end.completedEventIds());
    }

    static void mandatoryDuel() {
        CampaignService service = at(Stage.DUEL);
        CampaignSnapshot before = service.snapshot();
        equal(Result.WRONG_STAGE, advance(service));
        equal(before, service.snapshot());
        Duel duel = begin(service);
        equal(duel, begin(service));
        equal(Result.INCOMPLETE_DUEL, complete(service, duel));
        for (int i = 0; i < Duel.MAX_TICKS + Duel.WARNING_TICKS + Duel.FINISH_TICKS; i++) duel.step(false, false, true);
        equal(Duel.Phase.RESULT, duel.phase());
        equal(Result.INCOMPLETE_DUEL, complete(service, duel));
        check(!service.snapshot().joinedHawks(), "result display is not a completion grant");
        finish(duel);
        equal(Result.APPLIED, complete(service, duel));
    }

    static void terminalCompletion() {
        CampaignService service = ended();
        CampaignSnapshot end = service.snapshot();
        equal(Result.TERMINAL, advance(service));
        equal(Result.TERMINAL, service.resume(end.participantId()));
        equal(Result.TERMINAL, service.interrupt(end.participantId(), end.attemptId(), Interruption.PLAYER_DEATH));
        equal(Result.ALREADY_APPLIED, complete(service, new Duel()));
        fails(IllegalStateException.class, () -> begin(service));
        equal(end, service.snapshot());
    }

    static void staleRequests() {
        CampaignService service = fresh();
        CampaignSnapshot old = service.snapshot();
        equal(Result.APPLIED, advance(service));
        CampaignSnapshot after = service.snapshot();
        equal(Result.ALREADY_APPLIED, service.advance(old.participantId(), old.attemptId(), old.revision(), old.stage()));
        equal(Result.STALE_REVISION, service.advance(old.participantId(), old.attemptId(), old.revision(), Stage.TRANSPORT));
        equal(Result.STALE_REVISION, service.advance(old.participantId(), old.attemptId(), 100, old.stage()));
        equal(Result.WRONG_STAGE, service.advance(old.participantId(), old.attemptId(), after.revision(), Stage.GRIFFITH_DIALOGUE));
        equal(after, service.snapshot());
    }

    static void ownership() {
        CampaignService service = at(Stage.DUEL);
        CampaignSnapshot state = service.snapshot();
        UUID foreign = UUID.randomUUID();
        equal(Result.WRONG_PARTICIPANT, service.advance(foreign, state.attemptId(), state.revision(), state.stage()));
        equal(Result.STALE_ATTEMPT, service.advance(state.participantId(), foreign, state.revision(), state.stage()));
        equal(Result.WRONG_PARTICIPANT, service.interrupt(foreign, state.attemptId(), Interruption.CANCELLED));
        equal(Result.STALE_ATTEMPT, service.interrupt(state.participantId(), foreign, Interruption.CANCELLED));
        equal(Result.WRONG_PARTICIPANT, service.resume(foreign));
        equal(Result.WRONG_PARTICIPANT, service.completeDuel(foreign, state.attemptId(), state.revision(), new Duel()));
        fails(IllegalStateException.class, () -> service.beginDuel(foreign, state.attemptId(), state.revision()));
        equal(state, service.snapshot());
    }

    static void interruptionMatrix() {
        for (Stage stage : Stage.values()) {
            if (stage == Stage.END) continue;
            for (Interruption reason : Interruption.values()) {
                if (reason == Interruption.NONE) continue;
                CampaignService service = at(stage);
                CampaignSnapshot old = service.snapshot();
                equal(Result.APPLIED, service.interrupt(old.participantId(), old.attemptId(), reason));
                CampaignSnapshot paused = service.snapshot();
                equal(stage, paused.checkpoint());
                equal(Status.PAUSED, paused.status());
                equal(reason, paused.interruption());
                equal(old.appliedEffects(), paused.appliedEffects());
                equal(old.completedEventIds(), paused.completedEventIds());
                equal(Result.NOT_ACTIVE, advance(service));
                CampaignService loaded = CampaignService.restore(CampaignCodec.decode(CampaignCodec.encode(paused)));
                equal(paused, loaded.snapshot());
                equal(Result.APPLIED, loaded.resume(old.participantId()));
                equal(stage, loaded.snapshot().stage());
                check(!old.attemptId().equals(loaded.snapshot().attemptId()), "resume must renew the attempt");
                equal(old.appliedEffects(), loaded.snapshot().appliedEffects());
                equal(Result.STALE_ATTEMPT, loaded.advance(old.participantId(), old.attemptId(), old.revision(), old.stage()));
            }
        }
    }

    static void interruptedDuel() {
        CampaignService service = at(Stage.DUEL);
        Duel oldDuel = begin(service);
        CampaignSnapshot old = service.snapshot();
        service.interrupt(old.participantId(), old.attemptId(), Interruption.DISCONNECT);
        equal(Duel.Phase.ABORTED, oldDuel.phase());
        equal(Result.APPLIED, service.resume(old.participantId()));
        equal(Result.FOREIGN_DUEL, complete(service, oldDuel));
        Duel resumed = begin(service);
        check(oldDuel != resumed, "an interrupted duel must not be reused");
        equal(0, resumed.tick());
        finish(resumed);
        equal(Result.APPLIED, complete(service, resumed));
    }

    static void foreignDuel() {
        CampaignService service = at(Stage.DUEL);
        Duel debug = new Duel();
        finish(debug);
        equal(Result.FOREIGN_DUEL, complete(service, debug));
        begin(service);
        equal(Result.FOREIGN_DUEL, complete(service, debug));
        equal(Result.FOREIGN_DUEL, complete(service, null));
        equal(Stage.DUEL, service.snapshot().stage());
    }

    static void codecRoundTrips() {
        for (Stage stage : Stage.values()) {
            CampaignService service = stage == Stage.END ? ended() : at(stage);
            CampaignSnapshot state = service.snapshot();
            equal(state, CampaignCodec.decode(CampaignCodec.encode(state)));
            equal(state, CampaignCodec.fromFields(CampaignCodec.toFields(state)));
            equal(state, CampaignCodec.decode(CampaignCodec.encode(state).replace("\n", "\r\n")));
            if (stage != Stage.END) {
                service.interrupt(state.participantId(), state.attemptId(), Interruption.SAVE_EXIT);
                equal(service.snapshot(), CampaignCodec.decode(CampaignCodec.encode(service.snapshot())));
            }
        }
    }

    static void malformedCodec() {
        String valid = CampaignCodec.encode(fresh().snapshot());
        for (String value : new String[] {"", "schemaVersion=1\n", valid + "x=y\n", valid + "revision=0\n",
                valid.replace("revision=0", "revision=00"), valid.replace("revision=0", "revision=-1"),
                valid.replace("revision=0", "revision=9223372036854775808"), valid.replace("revision=0", "revision=0=1"),
                valid.replace("revision=0", "revision=\\u0030"), valid.replace("revision=0", "revision= 0"),
                valid.replace("revision=0", "#revision=0"), valid + "\n", valid.replace("revision=0\n", ""),
                "x".repeat(CampaignCodec.MAX_CHARACTERS + 1)})
            fails(IllegalArgumentException.class, () -> CampaignCodec.decode(value));
        fails(IllegalArgumentException.class, () -> CampaignCodec.decode(null));
    }

    static void unsupportedVersions() {
        CampaignSnapshot before = at(Stage.CASCA_DIALOGUE).snapshot();
        Map<String, String> fields = new HashMap<>(CampaignCodec.toFields(before));
        for (String version : new String[] {"0", "2", "2147483647", "2147483648"}) {
            fields.put("schemaVersion", version);
            fails(IllegalArgumentException.class, () -> CampaignCodec.fromFields(fields));
            equal(version, fields.get("schemaVersion"));
        }
        fields.put("schemaVersion", "1");
        fields.put("contentVersion", "first_episode_999");
        fails(IllegalArgumentException.class, () -> CampaignCodec.fromFields(fields));
        equal("first_episode_999", fields.get("contentVersion"));
        equal(Stage.CASCA_DIALOGUE, before.stage());
    }

    static void corruptLedger() {
        CampaignSnapshot end = ended().snapshot();
        rejectField(end, "flags", FIRST_DUEL_DONE);
        rejectField(end, "flags", String.join(",", end.flags()) + ",bekhilitt:camp_intro_available");
        rejectField(end, "flags", String.join(",", end.flags()) + "," + FIRST_DUEL_DONE);
        rejectField(end, "appliedEffects", "");
        rejectField(end, "appliedEffects", String.join(",", end.appliedEffects()) + ",foreign");
        rejectField(end, "appliedEffects", String.join(",", end.appliedEffects()).replace(end.campaignId().toString(), UUID.randomUUID().toString()));
        rejectField(end, "completedEventIds", "bekhilitt:first_raid");
        rejectField(end, "status", "ACTIVE");
        CampaignSnapshot start = fresh().snapshot();
        rejectField(start, "flags", FIRST_DUEL_DONE);
        rejectField(start, "status", "COMPLETE");
        rejectField(start, "interruption", "DISCONNECT");
        rejectField(start, "checkpoint", "DUEL");
    }

    static void immutability() {
        CampaignSnapshot before = at(Stage.TRANSPORT).snapshot();
        fails(UnsupportedOperationException.class, () -> before.appliedEffects().clear());
        fails(UnsupportedOperationException.class, () -> before.completedEventIds().add("future"));
        fails(UnsupportedOperationException.class, () -> before.flags().add(FIRST_DUEL_DONE));
        fails(UnsupportedOperationException.class, () -> CampaignCodec.toFields(before).put("revision", "123"));
        Set<String> mutableEvents = new HashSet<>(before.completedEventIds());
        Set<String> mutableEffects = new HashSet<>(before.appliedEffects());
        CampaignSnapshot copied = new CampaignSnapshot(before.schemaVersion(), before.contentVersion(), before.campaignId(),
                before.participantId(), before.revision(), before.chapterId(), before.checkpoint(), before.runId(),
                before.attemptId(), before.status(), before.interruption(), mutableEvents, before.flags(), mutableEffects);
        mutableEvents.clear(); mutableEffects.clear();
        equal(before, copied);
    }

    static void stableEffectKeys() {
        CampaignService service = at(Stage.CASCA_DIALOGUE);
        CampaignSnapshot before = service.snapshot();
        service.interrupt(before.participantId(), before.attemptId(), Interruption.DIALOGUE_CLOSED);
        service.resume(before.participantId());
        equal(before.appliedEffects(), service.snapshot().appliedEffects());
        check(!service.snapshot().attemptId().equals(before.attemptId()), "attempt not renewed");
        Map<String, String> fields = new HashMap<>(CampaignCodec.toFields(service.snapshot()));
        fields.put("runId", UUID.randomUUID().toString());
        fields.put("attemptId", UUID.randomUUID().toString());
        equal(before.appliedEffects(), CampaignCodec.fromFields(fields).appliedEffects());
        for (String effect : before.appliedEffects()) {
            check(effect.startsWith(before.campaignId() + "|bekhilitt:"), "campaign-scoped effect required");
            check(!effect.contains(before.runId().toString()) && !effect.contains(before.attemptId().toString()), "transient effect key");
        }
    }

    static void crashBeforeCommit() {
        CampaignService service = at(Stage.DUEL);
        Duel runtime = begin(service);
        finish(runtime);
        CampaignSnapshot persisted = service.snapshot();
        CampaignService recovered = CampaignService.restore(CampaignCodec.decode(CampaignCodec.encode(persisted)));
        equal(Stage.DUEL, recovered.snapshot().checkpoint());
        equal(Status.PAUSED, recovered.snapshot().status());
        equal(Interruption.SAVE_EXIT, recovered.snapshot().interruption());
        equal(persisted.revision() + 1, recovered.snapshot().revision());
        check(!recovered.snapshot().joinedHawks(), "uncommitted runtime result must not grant membership");
        equal(Result.APPLIED, recovered.resume(persisted.participantId()));
        equal(Result.FOREIGN_DUEL, complete(recovered, runtime));
        equal(0, begin(recovered).tick());
    }

    static void crashAfterCommit() {
        CampaignService service = at(Stage.DUEL);
        CampaignSnapshot before = service.snapshot();
        Duel duel = begin(service); finish(duel);
        equal(Result.APPLIED, complete(service, duel));
        CampaignSnapshot committed = service.snapshot();
        CampaignService recovered = CampaignService.restore(CampaignCodec.decode(CampaignCodec.encode(committed)));
        equal(committed, recovered.snapshot());
        equal(Result.ALREADY_APPLIED, recovered.completeDuel(before.participantId(), before.attemptId(), before.revision(), duel));
        equal(Result.TERMINAL, recovered.resume(before.participantId()));
        equal(committed, recovered.snapshot());
    }

    static void idempotentRecovery() {
        CampaignService service = fresh();
        CampaignSnapshot before = service.snapshot();
        equal(Result.ALREADY_APPLIED, service.resume(before.participantId()));
        equal(before, service.snapshot());
        equal(Result.APPLIED, service.interrupt(before.participantId(), before.attemptId(), Interruption.SAVE_EXIT));
        CampaignSnapshot paused = service.snapshot();
        equal(Result.ALREADY_APPLIED, service.interrupt(before.participantId(), before.attemptId(), Interruption.CANCELLED));
        equal(paused, service.snapshot());
        equal(Result.APPLIED, service.resume(before.participantId()));
        CampaignSnapshot resumed = service.snapshot();
        equal(Result.ALREADY_APPLIED, service.resume(before.participantId()));
        equal(resumed, service.snapshot());
    }

    static void revisionOverflow() {
        CampaignService service = fresh();
        CampaignSnapshot state = service.snapshot();
        service.interrupt(state.participantId(), state.attemptId(), Interruption.SAVE_EXIT);
        Map<String, String> fields = new HashMap<>(CampaignCodec.toFields(service.snapshot()));
        fields.put("revision", Long.toString(Long.MAX_VALUE));
        CampaignService loaded = CampaignService.restore(CampaignCodec.fromFields(fields));
        CampaignSnapshot before = loaded.snapshot();
        fails(ArithmeticException.class, () -> loaded.resume(before.participantId()));
        equal(before, loaded.snapshot());
    }

    static void deterministicCodec() {
        CampaignSnapshot state = ended().snapshot();
        String encoded = CampaignCodec.encode(state);
        for (int i = 0; i < 100; i++) equal(encoded, CampaignCodec.encode(CampaignCodec.decode(encoded)));
        var keys = new ArrayList<>(CampaignCodec.toFields(state).keySet());
        keys.sort(java.util.Comparator.reverseOrder());
        Map<String, String> reversed = new java.util.LinkedHashMap<>();
        keys.forEach(key -> reversed.put(key, CampaignCodec.toFields(state).get(key)));
        equal(encoded, CampaignCodec.encode(CampaignCodec.fromFields(reversed)));
    }

    static void identifiers() {
        CampaignSnapshot state = fresh().snapshot();
        for (String field : new String[] {"campaignId", "participantId", "runId", "attemptId"}) {
            rejectField(state, field, "0-0-0-0-1");
            rejectField(state, field, "00000000-0000-0000-0000-000000000000");
            rejectField(state, field, "not_a_uuid");
        }
        rejectField(state, "chapterId", "bekhilitt:future");
        rejectField(state, "sceneId", "bekhilitt:golden_age/first_duel");
        rejectField(state, "checkpointNodeId", "end");
        rejectField(state, "checkpoint", "UNKNOWN");
        rejectField(state, "status", "UNKNOWN");
        rejectField(state, "interruption", "UNKNOWN");
    }

    static void controlLease() {
        for (Stage stage : Stage.values()) {
            CampaignService service = stage == Stage.END ? ended() : at(stage);
            CampaignSnapshot state = service.snapshot();
            equal(stage == Stage.INJURY || stage == Stage.TRANSPORT || stage == Stage.RECOVERY, state.controlsLocked());
            if (stage != Stage.END) {
                service.interrupt(state.participantId(), state.attemptId(), Interruption.CANCELLED);
                check(!service.snapshot().controlsLocked(), "interruption must release controls");
            }
        }
    }

    static void adversarialEvents() {
        Random random = new Random(1997);
        CampaignService service = fresh();
        for (int i = 0; i < 2000; i++) {
            CampaignSnapshot before = service.snapshot();
            switch (random.nextInt(7)) {
                case 0 -> advance(service);
                case 1 -> service.interrupt(before.participantId(), before.attemptId(), Interruption.NPC_UNAVAILABLE);
                case 2 -> service.resume(before.participantId());
                case 3 -> service.advance(UUID.randomUUID(), before.attemptId(), before.revision(), before.stage());
                case 4 -> service.advance(before.participantId(), UUID.randomUUID(), before.revision(), before.stage());
                case 5 -> service.advance(before.participantId(), before.attemptId(), Math.max(0, before.revision() - 1), Stage.END);
                case 6 -> {
                    if (before.stage() == Stage.DUEL && before.status() == Status.ACTIVE) {
                        Duel duel = begin(service); finish(duel); complete(service, duel);
                    }
                }
            }
            CampaignSnapshot after = service.snapshot();
            check(after.stage().ordinal() >= before.stage().ordinal(), "checkpoint moved backward");
            check(after.stage().ordinal() <= before.stage().ordinal() + 1, "checkpoint skipped");
            check(after.revision() >= before.revision() && after.revision() <= before.revision() + 1, "invalid revision delta");
            check(after.appliedEffects().containsAll(before.appliedEffects()), "ledger was erased");
            equal(after, CampaignCodec.decode(CampaignCodec.encode(after)));
            if (before.complete()) equal(before, after);
        }
        check(service.snapshot().complete(), "seeded valid actions must eventually complete");
    }

    private static CampaignService fresh() { return CampaignService.create(UUID.randomUUID(), UUID.randomUUID()); }
    private static CampaignService at(Stage target) {
        CampaignService service = fresh();
        while (service.snapshot().stage() != target) {
            if (service.snapshot().stage() == Stage.DUEL) throw new IllegalArgumentException("Use ended() for END");
            equal(Result.APPLIED, advance(service));
        }
        return service;
    }
    private static CampaignService ended() {
        CampaignService service = at(Stage.DUEL);
        Duel duel = begin(service); finish(duel);
        equal(Result.APPLIED, complete(service, duel));
        return service;
    }
    private static Result advance(CampaignService service) {
        CampaignSnapshot state = service.snapshot();
        return service.advance(state.participantId(), state.attemptId(), state.revision(), state.stage());
    }
    private static Duel begin(CampaignService service) {
        CampaignSnapshot state = service.snapshot();
        return service.beginDuel(state.participantId(), state.attemptId(), state.revision());
    }
    private static Result complete(CampaignService service, Duel duel) {
        CampaignSnapshot state = service.snapshot();
        return service.completeDuel(state.participantId(), state.attemptId(), state.revision(), duel);
    }
    private static void finish(Duel duel) {
        int budget = Duel.MAX_TICKS + Duel.WARNING_TICKS + Duel.FINISH_TICKS + Duel.RESULT_TICKS + 1;
        while (duel.phase() != Duel.Phase.COMPLETE && budget-- > 0) duel.step(false, false, true);
        equal(Duel.Phase.COMPLETE, duel.phase());
    }
    private static void rejectField(CampaignSnapshot state, String field, String value) {
        Map<String, String> fields = new HashMap<>(CampaignCodec.toFields(state));
        fields.put(field, value);
        fails(IllegalArgumentException.class, () -> CampaignCodec.fromFields(fields));
    }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void fails(Class<? extends Throwable> expected, Runnable action) {
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " but operation succeeded");
    }
}
