package dev.bekhilitt.platform.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OwnerReturnVerifierTest {
    @TempDir Path world;
    private final EpisodeStore.ReturnPoint point = new EpisodeStore.ReturnPoint(
        UUID.fromString("76c77445-3344-5566-7788-99aabbccddee"), "minecraft:overworld", 1.5, -60, -5.5, 30, -15, "creative", true);

    private CompoundTag owner() {
        CompoundTag player = new CompoundTag();
        long most = point.owner().getMostSignificantBits(), least = point.owner().getLeastSignificantBits();
        player.putIntArray("UUID", new int[]{(int) (most >> 32), (int) most, (int) (least >> 32), (int) least});
        player.putString("Dimension", point.dimension()); player.putInt("playerGameType", 1);
        ListTag pos = new ListTag();
        for (double value : new double[]{point.x(), point.y(), point.z()}) pos.add(DoubleTag.valueOf(value));
        ListTag rotation = new ListTag(); rotation.add(FloatTag.valueOf(point.yaw())); rotation.add(FloatTag.valueOf(point.pitch()));
        player.put("Pos", pos); player.put("Rotation", rotation); return player;
    }
    private void write(CompoundTag player) throws IOException {
        CompoundTag root = new CompoundTag(), data = new CompoundTag();
        data.put("Player", player); root.put("Data", data); NbtIo.writeCompressed(root, world.resolve("level.dat"));
    }
    private void rejected(CompoundTag player) throws IOException {
        write(player); assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world, point));
        assertTrue(point.pending()); // Verification never consumes or rewrites a lease.
    }
    @Test void matchingSavedOwnerAccepts() throws IOException {
        write(owner()); assertDoesNotThrow(() -> OwnerReturnVerifier.verify(world, point));
    }
    @Test void oldCampPositionRejects() throws IOException {
        CompoundTag p = owner(); p.getList("Pos", 6).set(1, DoubleTag.valueOf(258)); rejected(p);
    }
    @Test void oldAdventureModeRejects() throws IOException {
        CompoundTag p = owner(); p.putInt("playerGameType", 2); rejected(p);
    }
    @Test void differentOwnerRejects() throws IOException {
        CompoundTag p = owner(); p.putIntArray("UUID", new int[]{1,2,3,4}); rejected(p);
    }
    @Test void differentDimensionRejects() throws IOException {
        CompoundTag p = owner(); p.putString("Dimension", "minecraft:the_nether"); rejected(p);
    }
    @Test void changedYawOrPitchRejects() throws IOException {
        CompoundTag p = owner(); p.getList("Rotation", 5).set(0, FloatTag.valueOf(90)); rejected(p);
        p = owner(); p.getList("Rotation", 5).set(1, FloatTag.valueOf(20)); rejected(p);
    }
    @Test void equivalentWrappedYawAccepts() throws IOException {
        CompoundTag p = owner(); p.getList("Rotation", 5).set(0, FloatTag.valueOf(390)); write(p);
        assertDoesNotThrow(() -> OwnerReturnVerifier.verify(world, point));
    }
    @Test void missingRequiredFieldRejects() throws IOException {
        for (String field : new String[]{"UUID", "Dimension", "playerGameType", "Pos", "Rotation"}) {
            CompoundTag p = owner(); p.remove(field); rejected(p);
        }
    }
    @Test void nonFinitePositionRejects() throws IOException {
        CompoundTag p = owner(); p.getList("Pos", 6).set(0, DoubleTag.valueOf(Double.NaN)); rejected(p);
    }
    @Test void missingOrCorruptLevelDatRejects() throws IOException {
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world, point));
        Files.writeString(world.resolve("level.dat"), "broken");
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world, point));
    }
    @Test void missingIntegratedOwnerRejects() throws IOException {
        NbtIo.writeCompressed(new CompoundTag(), world.resolve("level.dat"));
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world, point));
    }
    @Test void newerPlayerdataAloneCannotDisguiseOldIntegratedOwner() throws IOException {
        CompoundTag old = owner(); old.putInt("playerGameType", 2); write(old);
        Path players = Files.createDirectories(world.resolve("playerdata"));
        NbtIo.writeCompressed(owner(), players.resolve(point.owner() + ".dat"));
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world, point));
    }
    @Test void dedicatedReadsOnlyMatchingPlayerdata() throws IOException {
        Path players = Files.createDirectories(world.resolve("playerdata"));
        NbtIo.writeCompressed(owner(), players.resolve(point.owner() + ".dat"));
        assertDoesNotThrow(() -> OwnerReturnVerifier.verify(world,point,true));
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world,point,false));
    }
    @Test void dedicatedMissingPlayerdataDoesNotFallBackToLevelDat() throws IOException {
        write(owner());
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world,point,true));
    }
    @Test void dedicatedWrongOwnerFailsClosed() throws IOException {
        Path players = Files.createDirectories(world.resolve("playerdata"));
        CompoundTag p=owner(); p.putIntArray("UUID",new int[]{1,2,3,4});
        NbtIo.writeCompressed(p, players.resolve(point.owner() + ".dat"));
        assertThrows(IOException.class, () -> OwnerReturnVerifier.verify(world,point,true));
    }
}
