package dev.bekhilitt.combat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DuelTest {
    private void ticks(Duel d, int n, boolean reach, boolean facing) { for (int i=0;i<n;i++) d.step(reach,reach,facing); }
    @Test void attackRequiresWindupAndRejectsSpam() {
        Duel d = new Duel(); assertTrue(d.act(Duel.Action.ATTACK));
        assertFalse(d.act(Duel.Action.ATTACK)); ticks(d,5,true,true); assertEquals(0,d.landed());
        ticks(d,1,true,true); assertEquals(1,d.landed()); ticks(d,17,true,true); assertFalse(d.act(Duel.Action.ATTACK));
        ticks(d,1,true,true); assertTrue(d.act(Duel.Action.ATTACK));
    }
    @Test void missDoesNotCount() { Duel d=new Duel(); d.act(Duel.Action.ATTACK); ticks(d,6,false,true); assertEquals(0,d.landed()); }
    @Test void guardExpiresWhenInputStops() {
        Duel d=new Duel(); ticks(d,30,true,true); d.act(Duel.Action.GUARD); ticks(d,10,true,true);
        assertEquals(Duel.Impact.HIT,d.impact()); assertEquals(1,d.pressure());
    }
    @Test void explicitGuardReleaseIsImmediate() {
        Duel d=new Duel(); ticks(d,38,true,true); d.act(Duel.Action.GUARD);
        assertTrue(d.guarding()); d.releaseGuard(); assertFalse(d.guarding());
        ticks(d,2,true,true); assertEquals(Duel.Impact.HIT,d.impact());
    }
    @Test void guardBlocksOnlyWhenFacing() {
        for(boolean facing:new boolean[]{true,false}) {
            Duel d=new Duel(); ticks(d,38,true,true); d.act(Duel.Action.GUARD); ticks(d,2,true,facing);
            assertEquals(facing ? Duel.Impact.BLOCKED : Duel.Impact.HIT,d.impact());
        }
    }
    @Test void dodgeWindowAndCooldown() {
        Duel d=new Duel(); ticks(d,37,true,true); assertTrue(d.act(Duel.Action.DODGE));
        assertFalse(d.act(Duel.Action.DODGE)); ticks(d,3,true,true); assertEquals(Duel.Impact.EVADED,d.impact());
        ticks(d,20,true,true); assertFalse(d.act(Duel.Action.DODGE)); ticks(d,1,true,true); assertTrue(d.act(Duel.Action.DODGE));
    }
    @Test void idleDuelIsBoundedAndCompletionIsTerminal() {
        Duel d=new Duel(); ticks(d,Duel.MAX_TICKS,false,true); assertEquals(Duel.Phase.WARNING,d.phase());
        for(var a:Duel.Action.values()) assertFalse(d.act(a));
        ticks(d,Duel.WARNING_TICKS-1,false,true); assertEquals(Duel.Phase.WARNING,d.phase());
        ticks(d,1,false,true); assertEquals(Duel.Phase.FINISH,d.phase());
        ticks(d,Duel.FINISH_TICKS,false,true); assertEquals(Duel.Phase.RESULT,d.phase());
        ticks(d,Duel.RESULT_TICKS,false,true); assertEquals(Duel.Phase.COMPLETE,d.phase());
        int t=d.tick(); ticks(d,100,true,true); assertEquals(t,d.tick());
    }
    @Test void sixHitsDoNotCutOffPractice() {
        Duel d=new Duel(); ticks(d,340,true,true); assertEquals(6,d.pressure()); assertEquals(Duel.Phase.FIGHT,d.phase());
    }
    @Test void pressingKeysWithoutSuccessfulDefenseDoesNotFinish() {
        Duel d=new Duel(); d.act(Duel.Action.ATTACK); ticks(d,24,true,true);
        d.act(Duel.Action.GUARD); ticks(d,5,true,true); d.act(Duel.Action.DODGE); ticks(d,8,true,true);
        d.act(Duel.Action.ATTACK); ticks(d,6,true,true); assertEquals(2,d.landed());
        ticks(d,400,true,true); assertEquals(Duel.Phase.FIGHT,d.phase());
    }
    @Test void successfulPracticeWaitsAtLeastTwentySecondsThenWarns() {
        Duel d=new Duel(); d.act(Duel.Action.ATTACK); ticks(d,38,true,true);
        d.act(Duel.Action.GUARD); ticks(d,2,true,true); assertEquals(1,d.blocks());
        ticks(d,57,true,true); d.act(Duel.Action.DODGE); ticks(d,3,true,true); assertEquals(1,d.dodges());
        ticks(d,5,true,true); d.act(Duel.Action.ATTACK); ticks(d,6,true,true); assertEquals(2,d.landed());
        ticks(d,Duel.MIN_TICKS-d.tick()-1,true,true); assertEquals(Duel.Phase.FIGHT,d.phase());
        ticks(d,1,true,true); assertEquals(Duel.Phase.WARNING,d.phase());
    }
    @Test void dodgeOutOfRangeStillAcknowledgesEvadedCommittedAttack() {
        Duel d=new Duel(); ticks(d,37,true,true); d.act(Duel.Action.DODGE); ticks(d,3,false,true);
        assertEquals(Duel.Impact.EVADED,d.impact()); assertEquals(1,d.dodges());
    }
    @Test void abortDoesNotReportStoryCompletion() {
        Duel d=new Duel(); d.abort(); ticks(d,2000,true,true); assertEquals(Duel.Phase.ABORTED,d.phase()); assertFalse(d.act(Duel.Action.ATTACK));
    }
}
