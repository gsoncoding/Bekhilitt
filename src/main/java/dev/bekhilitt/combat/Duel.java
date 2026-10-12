package dev.bekhilitt.combat;

/** Tick-based, server-owned test duel. No campaign progress or Minecraft health is duplicated. */
public final class Duel {
    public enum Phase { FIGHT, WARNING, FINISH, RESULT, COMPLETE, ABORTED }
    public enum Action { ATTACK, GUARD, DODGE }
    public enum Impact { NONE, HIT, BLOCKED, EVADED }
    public static final int MIN_TICKS = 400, MAX_TICKS = 1200, WARNING_TICKS = 60, FINISH_TICKS = 80, RESULT_TICKS = 100;
    public static final int ATTACK_WINDUP = 6, ATTACK_RECOVERY = 18;
    public static final int DODGE_TICKS = 8, DODGE_COOLDOWN = 24, GUARD_LEASE = 5;
    private Phase phase = Phase.FIGHT;
    private int tick, finishTick, attack = -1, attackReady, dodgeUntil, dodgeReady, guardUntil;
    private int landed, pressure, blocks, dodges, phaseTick;
    private boolean enemyCommitted;
    private String finishReason = "";
    private Impact impact = Impact.NONE;
    public boolean act(Action action) {
        if (phase != Phase.FIGHT) return false;
        switch (action) {
            case ATTACK -> {
                if (tick < attackReady || tick < dodgeUntil || tick < guardUntil) return false;
                attack = tick + ATTACK_WINDUP; attackReady = attack + ATTACK_RECOVERY;
            }
            case GUARD -> {
                if (attack >= 0 || tick < dodgeUntil) return false;
                guardUntil = tick + GUARD_LEASE;
            }
            case DODGE -> {
                if (tick < dodgeReady || attack >= 0) return false;
                dodgeUntil = tick + DODGE_TICKS; dodgeReady = tick + DODGE_COOLDOWN;
                guardUntil = 0;
            }
        }
        return true;
    }
    public void step(boolean inPlayerReach, boolean inEnemyReach, boolean facingEnemy) {
        impact = Impact.NONE;
        if (phase == Phase.ABORTED || phase == Phase.COMPLETE) return;
        tick++;
        if (phase == Phase.WARNING) {
            if (++phaseTick >= WARNING_TICKS) { phase = Phase.FINISH; phaseTick = 0; }
            return;
        }
        if (phase == Phase.FINISH) {
            if (++finishTick >= FINISH_TICKS) { phase = Phase.RESULT; phaseTick = 0; }
            return;
        }
        if (phase == Phase.RESULT) {
            if (++phaseTick >= RESULT_TICKS) phase = Phase.COMPLETE;
            return;
        }
        if (attack == tick) { if (inPlayerReach) landed++; attack = -1; }
        // Every 60 ticks: 20 tick readable windup, then exactly one impact.
        if (tick % 60 == 20) enemyCommitted = inEnemyReach;
        if (tick % 60 == 40) {
            if ((enemyCommitted || inEnemyReach) && tick < dodgeUntil) { impact = Impact.EVADED; dodges++; }
            else if (inEnemyReach) {
                if (tick < guardUntil && facingEnemy) { impact = Impact.BLOCKED; blocks++; }
                else { impact = Impact.HIT; pressure++; }
            }
        }
        // Require demonstrated outcomes, not merely pressing keys; always announce staging.
        if ((tick >= MIN_TICKS && landed >= 2 && blocks > 0 && dodges > 0) || tick >= MAX_TICKS) {
            finishReason = tick >= MAX_TICKS ? "Время учебной дуэли истекло" : "Удар, блок и уклонение проверены";
            phase = Phase.WARNING; phaseTick = 0;
        }
    }
    public void abort() { if (phase != Phase.COMPLETE) phase = Phase.ABORTED; }
    public void releaseGuard() { guardUntil = 0; }
    public Phase phase() { return phase; }
    public int tick() { return tick; }
    public int finishTick() { return finishTick; }
    public int landed() { return landed; }
    public int pressure() { return pressure; }
    public int blocks() { return blocks; }
    public int dodges() { return dodges; }
    public int phaseTick() { return phaseTick; }
    public String finishReason() { return finishReason; }
    public boolean attacking() { return attack >= 0; }
    public Impact impact() { return impact; }
    public boolean windup() { return phase == Phase.FIGHT && tick % 60 >= 20 && tick % 60 < 40; }
    public boolean guarding() { return tick < guardUntil; }
    public boolean dodging() { return tick < dodgeUntil; }
}
