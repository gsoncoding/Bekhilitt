package dev.bekhilitt.input;

/** Pure context/edge gate. No bindings or Minecraft state are changed here. */
public final class CombatInput {
    public record Frame(boolean attack, boolean guard, boolean dodge, boolean releaseGuard) { }
    private boolean active, waitAttackRelease, waitUseRelease;
    private boolean attackArmed, guardArmed, dodgeArmed, guarding, wasEligible;

    public void session(boolean enabled, boolean vanillaAttackDown, boolean vanillaUseDown) {
        if (active == enabled) return;
        if (!enabled) {
            waitAttackRelease = vanillaAttackDown;
            waitUseRelease = vanillaUseDown;
        }
        active = enabled;
        wasEligible = false;
        attackArmed = guardArmed = dodgeArmed = false;
    }

    public Frame sample(boolean eligible, boolean attackDown, boolean attackClick,
                        boolean guardDown, boolean dodgeDown, boolean dodgeClick,
                        boolean vanillaAttackDown, boolean vanillaUseDown) {
        if (!vanillaAttackDown) waitAttackRelease = false;
        if (!vanillaUseDown) waitUseRelease = false;
        boolean wasGuarding = guarding;
        if (!active || !eligible) {
            wasEligible = false;
            attackArmed = guardArmed = dodgeArmed = guarding = false;
            return new Frame(false, false, false, wasGuarding);
        }
        if (!wasEligible) {
            wasEligible = true;
            attackArmed = !attackDown; guardArmed = !guardDown; dodgeArmed = !dodgeDown;
            guarding = false;
            return new Frame(false, false, false, wasGuarding);
        }
        if (!attackDown) attackArmed = true;
        if (!guardDown) guardArmed = true;
        if (!dodgeDown) dodgeArmed = true;
        boolean attack = attackArmed && attackClick;
        boolean dodge = dodgeArmed && dodgeClick;
        if (attack) attackArmed = false;
        if (dodge) dodgeArmed = false;
        guarding = guardArmed && guardDown;
        return new Frame(attack, guarding, dodge, wasGuarding && !guarding);
    }

    public boolean blockVanillaAttack() { return active || waitAttackRelease; }
    public boolean blockVanillaUse() { return active || waitUseRelease; }
    public void reset() {
        active = waitAttackRelease = waitUseRelease = false;
        attackArmed = guardArmed = dodgeArmed = guarding = wasEligible = false;
    }
}
