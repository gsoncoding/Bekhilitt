package dev.bekhilitt.platform.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PracticeLeaseVerifierTest {
    @TempDir Path world;
    private final UUID owner = UUID.randomUUID();
    private CompoundTag lease(boolean originalRule) {
        var lease = new CompoundTag();
        lease.putInt("format", 1); lease.putString("owner", owner.toString());
        lease.putString("attempt", "c3e0c9c4-d496-41af-ac0f-a314a7c10064");
        lease.putBoolean("leapWasLearned", false); lease.putBoolean("slamWasLearned", true);
        lease.putBoolean("mobGriefing", originalRule); return lease;
    }
    private CompoundTag root(CompoundTag lease, String rule) {
        var root = new CompoundTag(); var data = new CompoundTag(); var player = new CompoundTag();
        player.putUUID("UUID", owner); var extra = new CompoundTag();
        if (lease != null) extra.put(TrainingLeaseVerifier.PRACTICE_KEY, lease);
        player.put("NeoForgeData", extra); data.put("Player", player);
        var rules = new CompoundTag(); if (rule != null) rules.putString("mobGriefing", rule);
        data.put("GameRules", rules); root.put("Data", data); return root;
    }
    private void write(CompoundTag root) throws IOException { NbtIo.writeCompressed(root, world.resolve("level.dat")); }
    @Test void validOriginalTrueAndFalseLeaseAndRestorationAccepts() throws IOException {
        for (boolean rule : new boolean[]{true, false}) {
            var lease = lease(rule); TrainingLeaseVerifier.validatePractice(owner, lease);
            write(root(lease, Boolean.toString(rule)));
            assertDoesNotThrow(() -> TrainingLeaseVerifier.verifyPractice(world, owner, lease));
            assertDoesNotThrow(() -> TrainingLeaseVerifier.verifyPracticeRuleRestored(world, owner, lease));
        }
    }
    @Test void incompleteUnknownAndFutureVersionFailClosed() {
        for (String key : lease(true).getAllKeys()) {
            var broken = lease(true); broken.remove(key);
            assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(owner, broken));
        }
        var future = lease(true); future.putInt("format", 2);
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(owner, future));
        var unknown = lease(true); unknown.putString("extra", "ignored?");
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(owner, unknown));
    }
    @Test void wrongTypesBooleanAndIdentityAreRejected() {
        var type = lease(true); type.putString("format", "1");
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(owner, type));
        for (String key : new String[]{"leapWasLearned", "slamWasLearned", "mobGriefing"}) {
            var broken = lease(true); broken.putByte(key, (byte)2);
            assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(owner, broken));
        }
        var id = lease(true); id.putString("attempt", "invalid");
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(owner, id));
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validatePractice(UUID.randomUUID(), lease(true)));
    }
    @Test void changedRuleCannotClearRecoveryEvenWhenLeaseMatches() throws IOException {
        write(root(lease(true), "false"));
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verifyPracticeRuleRestored(world, owner, lease(true)));
        write(root(lease(false), "true"));
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verifyPracticeRuleRestored(world, owner, lease(false)));
    }
    @Test void missingOrMalformedRuleCannotClearRecovery() throws IOException {
        for (String rule : new String[]{null, "TRUE", "0", "unknown"}) {
            write(root(lease(true), rule));
            assertThrows(IOException.class, () -> TrainingLeaseVerifier.verifyPracticeRuleRestored(world, owner, lease(true)));
        }
    }
    @Test void leaseMustRemainOnDiskUntilRestoredRuleIsVerified() throws IOException {
        write(root(null, "true"));
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verifyPracticeRuleRestored(world, owner, lease(true)));
        var stale = lease(true); stale.putString("attempt", UUID.randomUUID().toString());
        write(root(stale, "true"));
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verifyPracticeRuleRestored(world, owner, lease(true)));
    }
    @Test void corruptSourceFailsWithoutOverwritingIt() throws IOException {
        Files.writeString(world.resolve("level.dat"), "damaged");
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verifyPractice(world, owner, lease(true)));
        assertEquals("damaged", Files.readString(world.resolve("level.dat")));
    }
}
