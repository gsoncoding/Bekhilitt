package dev.bekhilitt.combat;

import java.io.IOException;

/** Keep the durable practice protection until the original location/mode is verified. */
public final class PracticeRecovery {
    private PracticeRecovery() { }

    @FunctionalInterface
    public interface Step { void run() throws IOException; }

    /** A new/replayed story must never reinterpret an unresolved practice return. */
    public static void requireStoryStartAllowed(boolean practiceMarkerPresent) {
        if (practiceMarkerPresent)
            throw new IllegalStateException("Сначала восстановите незавершённый возврат практики. Новый запуск или resume не снимает защиту и не заменяет исходную запись.");
    }

    public static void restore(boolean trustedReturn, Step returnPlayer, Step releaseBorrowedState) throws IOException {
        if (!trustedReturn)
            throw new IllegalStateException("Исходная точка возврата не подтверждена. Защита практики сохранена; восстановите резервную копию кампании при закрытом мире.");
        // This step must save and verify the original position/mode before the
        // second step can remove the marker used by damage/cast quarantine.
        returnPlayer.run();
        releaseBorrowedState.run();
    }
}
