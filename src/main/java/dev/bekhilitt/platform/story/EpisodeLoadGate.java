package dev.bekhilitt.platform.story;

/** Bounded polling only; a platform preparation gate is not a new persistent story stage. */
public final class EpisodeLoadGate {
    public static final int MAX_TICKS = 200;
    public enum State { WAITING, READY, TIMED_OUT, CANCELLED }
    private State state = State.WAITING;
    private int ticks;
    public State poll(boolean ready) {
        if (state != State.WAITING) return state;
        ticks++;
        if (ready) state = State.READY;
        else if (ticks >= MAX_TICKS) state = State.TIMED_OUT;
        return state;
    }
    public void cancel() { if (state == State.WAITING) state = State.CANCELLED; }
    public State state() { return state; }
}
