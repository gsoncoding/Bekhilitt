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

class TrainingLeaseVerifierTest {
    @TempDir Path world;
    private final UUID owner = UUID.randomUUID();
    private CompoundTag lease() {
        CompoundTag lease = new CompoundTag();
        lease.putString("owner", owner.toString()); lease.putString("attempt", "45867544-bbcd-4cd3-876f-337722ed83da");
        lease.putBoolean("addedGuard", true); lease.putBoolean("addedDodge", true);
        lease.putBoolean("guardWasLearned", false); lease.putBoolean("dodgeWasLearned", true);
        lease.putString("mode", "VANILLA"); lease.putFloat("health", 13.5F); lease.putFloat("stamina", 7);
        lease.putInt("food", 18); lease.putFloat("saturation", 3.5F); lease.putFloat("exhaustion", 1.5F);
        return lease;
    }
    private CompoundTag player() {
        CompoundTag player = new CompoundTag(); player.putUUID("UUID", owner);
        CompoundTag extra = new CompoundTag(); extra.put(TrainingLeaseVerifier.LEASE_KEY, lease());
        player.put("NeoForgeData", extra); return player;
    }
    private void integrated(CompoundTag player) throws IOException {
        CompoundTag root = new CompoundTag(), data = new CompoundTag();
        data.put("Player", player); root.put("Data", data);
        NbtIo.writeCompressed(root, world.resolve("level.dat"));
    }
    private void dedicated(CompoundTag player) throws IOException {
        Files.createDirectories(world.resolve("playerdata"));
        NbtIo.writeCompressed(player, world.resolve("playerdata").resolve(owner + ".dat"));
    }
    @Test void exactIntegratedSnapshotAccepts() throws IOException {
        integrated(player()); assertDoesNotThrow(() -> TrainingLeaseVerifier.verify(world, owner, false, lease()));
    }
    @Test void exactDedicatedSnapshotAccepts() throws IOException {
        dedicated(player()); assertDoesNotThrow(() -> TrainingLeaseVerifier.verify(world, owner, true, lease()));
    }
    @Test void wrongRecoverySourceNeverSubstitutesForTheAuthoritativeOne() throws IOException {
        integrated(player());
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, true, lease()));
        Files.delete(world.resolve("level.dat")); dedicated(player());
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, false, lease()));
    }
    @Test void staleAttemptOrChangedHealthRejects() throws IOException {
        CompoundTag stale = player();
        stale.getCompound("NeoForgeData").getCompound(TrainingLeaseVerifier.LEASE_KEY).putString("attempt", "old");
        integrated(stale);
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, false, lease()));
        stale = player(); stale.getCompound("NeoForgeData").getCompound(TrainingLeaseVerifier.LEASE_KEY).putFloat("health", 1);
        integrated(stale);
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, false, lease()));
    }
    @Test void missingLeaseOrWrongOwnerRejects() throws IOException {
        CompoundTag wrong = player(); wrong.remove("NeoForgeData"); integrated(wrong);
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, false, lease()));
        wrong = player(); wrong.putUUID("UUID", UUID.randomUUID()); dedicated(wrong);
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, true, lease()));
    }
    @Test void corruptFileFailsWithoutChangingIt() throws IOException {
        Files.writeString(world.resolve("level.dat"), "not-nbt");
        assertThrows(IOException.class, () -> TrainingLeaseVerifier.verify(world, owner, false, lease()));
        assertEquals("not-nbt", Files.readString(world.resolve("level.dat")));
    }
    @Test void validRecoveryStateAccepts() {
        assertDoesNotThrow(() -> TrainingLeaseVerifier.validate(owner, lease()));
    }
    @Test void missingFieldOrWrongTypeFailsBeforeRestoration() {
        for (String key : lease().getAllKeys()) {
            CompoundTag broken = lease(); broken.remove(key);
            assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validate(owner, broken));
        }
        CompoundTag broken = lease(); broken.putString("food", "18");
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validate(owner, broken));
    }
    @Test void corruptNumericOrIdentityStateFailsBeforeRestoration() {
        for (float amount : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1}) {
            CompoundTag broken = lease(); broken.putFloat("health", amount);
            assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validate(owner, broken));
        }
        CompoundTag broken = lease(); broken.putByte("addedGuard", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validate(owner, broken));
        assertThrows(IllegalArgumentException.class, () -> TrainingLeaseVerifier.validate(UUID.randomUUID(), lease()));
    }
}
