package dev.bekhilitt.combat;

import java.util.Objects;
import java.util.UUID;

/** Safety boundary only; never calculates attack power, colliders or defense windows. */
public final class TrainingCombatRules {
    public static final float HEALTH_FLOOR = 2.0F;
    public static final float MAX_TRAINING_PLAYER_DAMAGE = 1.0F;
    private TrainingCombatRules() { }

    public static boolean cancelDamage(UUID participant, UUID opponent, boolean fighting,
                                       UUID victim, UUID attacker, boolean epicFightSource) {
        Objects.requireNonNull(participant);
        Objects.requireNonNull(victim);
        boolean victimPlayer = participant.equals(victim);
        boolean victimOpponent = opponent != null && opponent.equals(victim);
        boolean sourcePlayer = participant.equals(attacker);
        boolean sourceOpponent = opponent != null && opponent.equals(attacker);
        if (!victimPlayer && !victimOpponent && !sourcePlayer && !sourceOpponent) return false;
        return !fighting || !epicFightSource
            || !((victimPlayer && sourceOpponent) || (victimOpponent && sourcePlayer));
    }

    /** Applies only after a real native hit has passed defense and damage modifiers. */
    public static boolean quarantinePractice(boolean leasePresent, boolean safeActivePractice) {
        return leasePresent && !safeActivePractice;
    }
    public static boolean protectPracticeDamage(boolean victimLeased, boolean attackerLeased, boolean livePracticeInvolved) {
        return victimLeased || attackerLeased || livePracticeInvolved;
    }

    public static float trainingPlayerDamage(float amount, float health) {
        return Math.min(MAX_TRAINING_PLAYER_DAMAGE, nonlethalDamage(amount, health));
    }

    public static float nonlethalDamage(float amount, float health) {
        if (!Float.isFinite(amount) || !Float.isFinite(health) || amount <= 0) return 0;
        return Math.max(0, Math.min(amount, health - HEALTH_FLOOR));
    }
}
