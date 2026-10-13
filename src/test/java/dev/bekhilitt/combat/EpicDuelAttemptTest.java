package dev.bekhilitt.combat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EpicDuelAttemptTest {
    private final UUID player = UUID.randomUUID(), attempt = UUID.randomUUID(), opponent = UUID.randomUUID();
    private EpicDuelAttempt duel() {
        var duel = new EpicDuelAttempt(player, attempt, opponent);
        ticks(duel, EpicDuelAttempt.PREPARATION_TICKS);
        return duel;
    }
    private static void ticks(EpicDuelAttempt duel, int count) { for (int i = 0; i < count; i++) duel.tick(); }

    @Test void countersRequireResolvedPositiveDamageAndMatchingEntities() {
        var duel = duel();
        assertFalse(duel.observeDamage(player, opponent, 0));
        assertFalse(duel.observeDamage(player, opponent, -1));
        assertFalse(duel.observeDamage(player, opponent, Float.NaN));
        assertFalse(duel.observeDamage(player, opponent, Float.POSITIVE_INFINITY));
        assertFalse(duel.observeDamage(UUID.randomUUID(), opponent, 1));
        assertFalse(duel.observeDamage(player, UUID.randomUUID(), 1));
        assertEquals(0, duel.landed());
        assertTrue(duel.observeDamage(player, opponent, 0.25f));
        assertEquals(1, duel.landed());
        assertTrue(duel.observeDamage(opponent, player, 2));
        assertEquals(1, duel.received());
    }

    @Test void guardAndDodgeMustBelongToThisOpponent() {
        var duel = duel();
        assertFalse(duel.observeGuard(player, opponent));
        assertFalse(duel.observeDodge(player, opponent));
        assertFalse(duel.observeGuard(UUID.randomUUID(), player));
        assertFalse(duel.observeDodge(UUID.randomUUID(), player));
        assertTrue(duel.observeGuard(opponent, player));
        assertTrue(duel.observeDodge(opponent, player));
        assertEquals(1, duel.blocks());
        assertEquals(1, duel.dodges());
        assertEquals(4, duel.consumeFeedback());
        assertEquals(0, duel.consumeFeedback());
    }

    @Test void trainingAllowsARealMinimumFightBeforeCoda() {
        var duel = duel();
        duel.observeDamage(player, opponent, 1);
        duel.observeDamage(player, opponent, 1);
        duel.observeGuard(opponent, player);
        duel.observeDodge(opponent, player);
        ticks(duel, EpicDuelAttempt.MIN_FIGHT_TICKS - 1);
        assertEquals(EpicDuelAttempt.Phase.FIGHT, duel.phase());
        duel.tick();
        assertEquals(EpicDuelAttempt.Phase.WARNING, duel.phase());
        assertEquals(EpicDuelAttempt.FinishReason.TRAINING_OBSERVED, duel.finishReason());
    }

    @Test void timerDoesNotInventSuccessfulTraining() {
        var duel = duel();
        ticks(duel, EpicDuelAttempt.MAX_FIGHT_TICKS);
        assertEquals(EpicDuelAttempt.FinishReason.TIME_LIMIT, duel.finishReason());
        assertEquals(EpicDuelAttempt.MAX_FIGHT_TICKS, duel.fightTicks());
        ticks(duel, 20);
        assertEquals(EpicDuelAttempt.MAX_FIGHT_TICKS, duel.fightTicks());
        assertFalse(duel.trainingObserved());
        assertEquals(0, duel.landed());
        assertEquals(0, duel.blocks());
        assertEquals(0, duel.dodges());
    }

    @Test void nonlethalExhaustionCannotBeTriggeredByForeignEntity() {
        var duel = duel();
        assertFalse(duel.exhausted(UUID.randomUUID()));
        assertTrue(duel.exhausted(player));
        assertEquals(EpicDuelAttempt.FinishReason.PLAYER_EXHAUSTED, duel.finishReason());
        assertFalse(duel.exhausted(opponent));
        assertFalse(duel.observeDamage(player, opponent, 4));
        assertFalse(duel.observeGuard(opponent, player));
        assertFalse(duel.observeDodge(opponent, player));
    }

    @Test void opponentExhaustionStillHasTheCanonicalStoryCoda() {
        var duel = duel();
        assertTrue(duel.exhausted(opponent));
        assertEquals(EpicDuelAttempt.FinishReason.OPPONENT_EXHAUSTED, duel.finishReason());
        assertFalse(duel.controlsLocked());
        ticks(duel, EpicDuelAttempt.WARNING_TICKS);
        assertEquals(EpicDuelAttempt.Phase.FINISH, duel.phase());
        assertTrue(duel.controlsLocked());
        ticks(duel, EpicDuelAttempt.FINISH_TICKS);
        assertEquals(EpicDuelAttempt.Phase.RESULT, duel.phase());
        ticks(duel, EpicDuelAttempt.RESULT_TICKS);
        assertEquals(EpicDuelAttempt.Phase.COMPLETE, duel.phase());
        int terminalTick = duel.ticks();
        duel.tick();
        assertEquals(terminalTick, duel.ticks());
    }

    @Test void interruptionNeverCompletesTheDuel() {
        var duel = duel();
        duel.abort();
        ticks(duel, 5000);
        assertEquals(EpicDuelAttempt.Phase.ABORTED, duel.phase());
        assertEquals(EpicDuelAttempt.PREPARATION_TICKS, duel.ticks());
        assertFalse(duel.exhausted(player));
        assertFalse(duel.observeDamage(player, opponent, 3));
    }

    @Test void preparationLastsExactlyThreeSecondsAndCannotGrantTraining() {
        var duel = new EpicDuelAttempt(player, attempt, opponent);
        assertEquals(EpicDuelAttempt.Phase.PREPARATION, duel.phase());
        assertFalse(duel.fighting());
        assertFalse(duel.observeDamage(player, opponent, 2));
        assertFalse(duel.observeDamage(opponent, player, 2));
        assertFalse(duel.observeGuard(opponent, player));
        assertFalse(duel.observeDodge(opponent, player));
        assertFalse(duel.exhausted(player));
        ticks(duel, EpicDuelAttempt.PREPARATION_TICKS - 1);
        assertEquals(EpicDuelAttempt.Phase.PREPARATION, duel.phase());
        duel.tick();
        assertTrue(duel.fighting());
        assertEquals(0, duel.phaseTick());
        assertEquals(0, duel.fightTicks());
        assertEquals(0, duel.landed());
        assertEquals(0, duel.blocks());
        assertEquals(0, duel.dodges());
        assertTrue(duel.observeDamage(player, opponent, 1));
    }

    @Test void interruptedPreparationCannotStartLater() {
        var duel = new EpicDuelAttempt(player, attempt, opponent);
        ticks(duel, 30);
        duel.abort();
        ticks(duel, 3000);
        assertEquals(EpicDuelAttempt.Phase.ABORTED, duel.phase());
        assertEquals(30, duel.ticks());
        assertFalse(duel.fighting());
    }

    @Test void selfOpponentRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EpicDuelAttempt(player, attempt, player));
    }
}
