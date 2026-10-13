package dev.bekhilitt.platform.story;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EpisodeLoadGateTest {
    @Test void noSceneStartsBeforeReadiness() {
        var gate = new EpisodeLoadGate();
        for (int i = 1; i < EpisodeLoadGate.MAX_TICKS; i++)
            assertEquals(EpisodeLoadGate.State.WAITING, gate.poll(false));
    }
    @Test void readinessBecomesTerminalOnlyOnce() {
        var gate = new EpisodeLoadGate(); gate.poll(false);
        assertEquals(EpisodeLoadGate.State.READY, gate.poll(true));
        for (int i = 0; i < 300; i++) assertEquals(EpisodeLoadGate.State.READY, gate.poll(false));
    }
    @Test void timeoutPreventsLateStart() {
        var gate = new EpisodeLoadGate();
        for (int i = 0; i < EpisodeLoadGate.MAX_TICKS; i++) gate.poll(false);
        assertEquals(EpisodeLoadGate.State.TIMED_OUT, gate.state());
        assertEquals(EpisodeLoadGate.State.TIMED_OUT, gate.poll(true));
    }
    @Test void cancellationPreventsLateStart() {
        var gate = new EpisodeLoadGate(); gate.poll(false); gate.cancel();
        assertEquals(EpisodeLoadGate.State.CANCELLED, gate.poll(true));
        assertEquals(EpisodeLoadGate.State.CANCELLED, gate.poll(false));
    }
    @Test void readinessOnLastAllowedTickAccepts() {
        var gate = new EpisodeLoadGate();
        for (int i = 1; i < EpisodeLoadGate.MAX_TICKS; i++) gate.poll(false);
        assertEquals(EpisodeLoadGate.State.READY, gate.poll(true));
    }
}
