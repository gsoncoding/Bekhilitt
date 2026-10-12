package dev.bekhilitt.combat;

/** Tick-based, server-owned test duel. No campaign progress or Minecraft health is duplicated. */
public final class Duel {
    public enum Phase { FIGHT, FINISH, COMPLETE, ABORTED }
    public enum Action { ATTACK, GUARD, DODGE }
    public enum Impact { NONE, HIT, BLOCKED, EVADED }
    public static final int MAX_TICKS = 900, FINISH_TICKS = 80;
    public static final int ATTACK_WINDUP = 6, ATTACK_RECOVERY = 18;
    public static final int DODGE_TICKS = 8, DODGE_COOLDOWN = 24, GUARD_LEASE = 5;
    private Phase phase = Phase.FIGHT;
    private int tick, finishTick, attack = -1, attackReady, dodgeUntil, dodgeReady, guardUntil;
    private int landed, pressure;
    private boolean attacked, guarded, dodged;
    private Impact impact = Impact.NONE;
    public boolean act(Action action) {
        if (phase != Phase.FIGHT) return false;
        switch (action) {
            case ATTACK -> {
                if (tick < attackReady || tick < dodgeUntil || tick < guardUntil) return false;
                attack = tick + ATTACK_WINDUP; attackReady = attack + ATTACK_RECOVERY; attacked = true;
            }
            case GUARD -> {
                if (attack >= 0 || tick < dodgeUntil) return false;
                guardUntil = tick + GUARD_LEASE; guarded = true;
            }
            case DODGE -> {
                if (tick < dodgeReady || attack >= 0) return false;
                dodgeUntil = tick + DODGE_TICKS; dodgeReady = tick + DODGE_COOLDOWN;
                guardUntil = 0; dodged = true;
            }
        }
        return true;
    }
    public void step(boolean inPlayerReach, boolean inEnemyReach, boolean facingEnemy) {
        impact = Impact.NONE;
        if (phase == Phase.ABORTED || phase == Phase.COMPLETE) return;
        tick++;
        if (phase == Phase.FINISH) {
            if (++finishTick >= FINISH_TICKS) phase = Phase.COMPLETE;
            return;
        }
        if (attack == tick) { if (inPlayerReach) landed++; attack = -1; }
        // Every 60 ticks: 20 tick readable windup, then exactly one impact.
        if (tick % 60 == 40 && inEnemyReach) {
            if (tick < dodgeUntil) impact = Impact.EVADED;
            else if (tick < guardUntil && facingEnemy) impact = Impact.BLOCKED;
            else { impact = Impact.HIT; pressure++; }
        }
        // Bounded tutorial: actions + two contacts, six received hits, or 45 s timeout.
        if ((attacked && guarded && dodged && landed >= 2) || pressure >= 6 || tick >= MAX_TICKS)
            phase = Phase.FINISH;
    }
    public void abort() { if (phase != Phase.COMPLETE) phase = Phase.ABORTED; }
    public Phase phase() { return phase; }
    public int tick() { return tick; }
    public int finishTick() { return finishTick; }
    public int landed() { return landed; }
    public int pressure() { return pressure; }
    public Impact impact() { return impact; }
    public boolean windup() { return phase == Phase.FIGHT && tick % 60 >= 20 && tick % 60 < 40; }
    public boolean guarding() { return tick < guardUntil; }
    public boolean dodging() { return tick < dodgeUntil; }
}
