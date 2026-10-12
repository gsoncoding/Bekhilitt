package dev.bekhilitt.input;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatInputTest {
    private final CombatInput input = new CombatInput();
    private CombatInput.Frame frame(boolean eligible, boolean attack, boolean click, boolean guard, boolean dodge, boolean dodgeClick) {
        return input.sample(eligible, attack, click, guard, dodge, dodgeClick, attack, guard);
    }
    private void enter() { input.session(true, false, false); frame(true, false, false, false, false, false); }

    @Test void inactiveLeavesVanillaUnchangedAndEmitsNothing() {
        var f = frame(true, true, true, true, true, true);
        assertFalse(f.attack()); assertFalse(f.guard()); assertFalse(f.dodge());
        assertFalse(input.blockVanillaAttack()); assertFalse(input.blockVanillaUse());
    }
    @Test void duelBlocksVanillaEvenWhilePausedOrStaging() {
        enter(); frame(false, false, false, false, false, false);
        assertTrue(input.blockVanillaAttack()); assertTrue(input.blockVanillaUse());
    }
    @Test void quickMouseClickBetweenTicksIsNotLost() {
        enter(); assertTrue(frame(true, false, true, false, false, false).attack());
        assertFalse(frame(true, false, false, false, false, false).attack());
    }
    @Test void heldAttackAndDodgeDoNotAutoRepeat() {
        enter(); var first = frame(true, true, true, false, true, true);
        assertTrue(first.attack()); assertTrue(first.dodge());
        var repeat = frame(true, true, true, false, true, true);
        assertFalse(repeat.attack()); assertFalse(repeat.dodge());
        frame(true, false, false, false, false, false);
        assertTrue(frame(true, true, true, false, false, false).attack());
    }
    @Test void guardRenewsOnlyWhileHeldAndReleasesOnce() {
        enter(); assertTrue(frame(true, false, false, true, false, false).guard());
        assertTrue(frame(true, false, false, true, false, false).guard());
        var released = frame(true, false, false, false, false, false);
        assertFalse(released.guard()); assertTrue(released.releaseGuard());
        assertFalse(frame(true, false, false, false, false, false).releaseGuard());
    }
    @Test void menuOrLostFocusDropsGuardAndRequiresReleaseBeforeResuming() {
        enter(); frame(true, false, false, true, false, false);
        assertTrue(frame(false, false, false, true, false, false).releaseGuard());
        assertFalse(frame(true, false, false, true, false, false).guard());
        assertFalse(frame(true, false, false, true, false, false).guard());
        frame(true, false, false, false, false, false);
        assertTrue(frame(true, false, false, true, false, false).guard());
    }
    @Test void queuedClicksAndHeldButtonsAtEntryAreDiscarded() {
        input.session(true, true, true);
        var first = frame(true, true, true, true, true, true);
        assertFalse(first.attack()); assertFalse(first.guard()); assertFalse(first.dodge());
        assertFalse(frame(true, true, true, true, true, true).attack());
        frame(true, false, false, false, false, false);
        assertTrue(frame(true, true, true, false, false, false).attack());
    }
    @Test void exitingWithHeldMouseDoesNotLeakIntoVanilla() {
        enter(); frame(true, true, true, true, false, false);
        input.session(false, true, true);
        assertTrue(input.blockVanillaAttack()); assertTrue(input.blockVanillaUse());
        assertTrue(frame(true, true, false, true, false, false).releaseGuard());
        frame(true, false, false, false, false, false);
        assertFalse(input.blockVanillaAttack()); assertFalse(input.blockVanillaUse());
    }
    @Test void disconnectClearsAllContextAndReleaseLatches() {
        enter(); input.session(false, true, true); input.reset();
        assertFalse(input.blockVanillaAttack()); assertFalse(input.blockVanillaUse());
        assertFalse(frame(true, true, true, true, true, true).guard());
    }
}
