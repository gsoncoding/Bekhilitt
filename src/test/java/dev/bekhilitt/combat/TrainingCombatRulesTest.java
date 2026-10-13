package dev.bekhilitt.combat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrainingCombatRulesTest {
    private final UUID player = UUID.randomUUID(), enemy = UUID.randomUUID(), outsider = UUID.randomUUID();

    @Test void onlyMatchingLiveEpicFightPairMayDamageEachOther() {
        assertFalse(cancel(true, player, enemy, true));
        assertFalse(cancel(true, enemy, player, true));
        assertTrue(cancel(false, player, enemy, true));
        assertTrue(cancel(false, enemy, player, true));
        assertTrue(cancel(true, player, enemy, false));
        assertTrue(cancel(true, enemy, player, false));
    }

    @Test void environmentAndUnrelatedAttackersCannotKillSceneActors() {
        assertTrue(cancel(true, player, null, false));
        assertTrue(cancel(true, enemy, null, false));
        assertTrue(cancel(true, player, outsider, true));
        assertTrue(cancel(true, enemy, outsider, true));
        assertTrue(cancel(true, player, player, true));
        assertTrue(cancel(true, enemy, enemy, true));
    }

    @Test void sceneAttacksCannotLeakOntoBystanders() {
        assertTrue(cancel(true, outsider, player, true));
        assertTrue(cancel(true, outsider, enemy, true));
        assertFalse(cancel(false, outsider, UUID.randomUUID(), false));
        assertFalse(cancel(false, outsider, null, false));
    }

    @Test void loadingWithoutOpponentStillProtectsPlayer() {
        assertTrue(TrainingCombatRules.cancelDamage(player, null, false, player, outsider, false));
        assertTrue(TrainingCombatRules.cancelDamage(player, null, false, outsider, player, false));
        assertFalse(TrainingCombatRules.cancelDamage(player, null, false, outsider, null, false));
    }

    @Test void lethalDamageIsCappedAfterNativeCalculation() {
        assertEquals(3.0F, TrainingCombatRules.nonlethalDamage(3, 20));
        assertEquals(18.0F, TrainingCombatRules.nonlethalDamage(1000, 20));
        assertEquals(0.5F, TrainingCombatRules.nonlethalDamage(3, 2.5F));
        assertEquals(0.0F, TrainingCombatRules.nonlethalDamage(3, 2));
        assertEquals(0.0F, TrainingCombatRules.nonlethalDamage(3, 1));
    }

    @Test void malformedDamageNeverIncreasesOrCorruptsHealth() {
        for (float damage : new float[]{-1, 0, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertEquals(0.0F, TrainingCombatRules.nonlethalDamage(damage, 20));
        for (float health : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -10})
            assertEquals(0.0F, TrainingCombatRules.nonlethalDamage(4, health));
    }

    @Test void trainingCapKeepsNativeSubCapDamageAndNonlethalFloor() {
        assertEquals(1.0F, TrainingCombatRules.trainingPlayerDamage(1000, 20));
        assertEquals(1.0F, TrainingCombatRules.trainingPlayerDamage(7, 20));
        assertEquals(0.75F, TrainingCombatRules.trainingPlayerDamage(0.75F, 20));
        assertEquals(0.5F, TrainingCombatRules.trainingPlayerDamage(7, 2.5F));
        assertEquals(0.0F, TrainingCombatRules.trainingPlayerDamage(7, 2));
        assertEquals(0.0F, TrainingCombatRules.trainingPlayerDamage(Float.NaN, 20));
        assertEquals(0.0F, TrainingCombatRules.trainingPlayerDamage(Float.POSITIVE_INFINITY, 20));
        assertEquals(0.0F, TrainingCombatRules.trainingPlayerDamage(-1, 20));
    }

    @Test void fullHealthTrainingPlayerCanReceiveEighteenUnblockedHitsBeforeExhaustion() {
        float health = 20;
        for (int i = 0; i < 17; i++) health -= TrainingCombatRules.trainingPlayerDamage(7, health);
        assertEquals(3.0F, health);
        health -= TrainingCombatRules.trainingPlayerDamage(7, health);
        assertEquals(TrainingCombatRules.HEALTH_FLOOR, health);
    }

    @Test void unresolvedPracticeIsQuarantinedWithoutALiveSessionOrValidCompound() {
        // Presence is checked independently of NBT type/validity and session lifetime.
        assertTrue(TrainingCombatRules.quarantinePractice(true, false));
        assertFalse(TrainingCombatRules.quarantinePractice(true, true));
        assertFalse(TrainingCombatRules.quarantinePractice(false, false));
        assertFalse(TrainingCombatRules.quarantinePractice(false, true));
    }

    @Test void unresolvedPracticeDamageProtectionSurvivesSessionLoss() {
        assertTrue(TrainingCombatRules.protectPracticeDamage(true, false, false));
        assertTrue(TrainingCombatRules.protectPracticeDamage(false, true, false));
        assertTrue(TrainingCombatRules.protectPracticeDamage(false, false, true));
        assertFalse(TrainingCombatRules.protectPracticeDamage(false, false, false));
    }

    private boolean cancel(boolean active, UUID victim, UUID attacker, boolean nativeSource) {
        return TrainingCombatRules.cancelDamage(player, enemy, active, victim, attacker, nativeSource);
    }
}
