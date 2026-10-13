package dev.bekhilitt.platform.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Verifies the on-disk source the integrated server actually prefers for its owner on login. */
public final class OwnerReturnVerifier {
    private static final long MAX_COMPRESSED_BYTES = 16L * 1024 * 1024;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private OwnerReturnVerifier() { }

    public static void verify(Path world, EpisodeStore.ReturnPoint expected) throws IOException {
        verify(world, expected, false);
    }

    /** Dedicated servers load UUID.dat; integrated owners load level.dat even when UUID.dat is newer. */
    public static void verify(Path world, EpisodeStore.ReturnPoint expected, boolean dedicated) throws IOException {
        Path level = dedicated ? world.resolve("playerdata").resolve(expected.owner() + ".dat") : world.resolve("level.dat");
        try {
            if (!Files.isRegularFile(level) || Files.size(level) > MAX_COMPRESSED_BYTES)
                throw new IOException("Missing or oversized player save; recovery lease retained");
            CompoundTag root = NbtIo.readCompressed(level, NbtAccounter.create(MAX_NBT_BYTES));
            if (!dedicated && (!root.contains("Data", 10) || !root.getCompound("Data").contains("Player", 10)))
                throw new IOException("Saved integrated owner is missing; recovery lease retained");
            CompoundTag player = dedicated ? root : root.getCompound("Data").getCompound("Player");
            verifyOwner(player, expected);
        } catch (RuntimeException error) {
            throw new IOException("Saved integrated owner cannot be verified; recovery lease retained", error);
        }
    }

    private static void verifyOwner(CompoundTag player, EpisodeStore.ReturnPoint expected) throws IOException {
        UUID owner = expected.owner();
        long most = owner.getMostSignificantBits(), least = owner.getLeastSignificantBits();
        int[] uuid = {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
        int mode = switch (expected.mode()) {
            case "survival" -> 0; case "creative" -> 1; case "adventure" -> 2; case "spectator" -> 3;
            default -> throw new IOException("Unknown return mode");
        };
        ListTag position = player.getList("Pos", 6);
        ListTag rotation = player.getList("Rotation", 5);
        boolean matches = Arrays.equals(player.getIntArray("UUID"), uuid)
            && player.contains("Dimension", 8) && expected.dimension().equals(player.getString("Dimension"))
            && player.contains("playerGameType", 3) && player.getInt("playerGameType") == mode
            && position.size() == 3 && rotation.size() == 2
            && close(position.getDouble(0), expected.x())
            && close(position.getDouble(1), expected.y())
            && close(position.getDouble(2), expected.z())
            && sameYaw(rotation.getFloat(0), expected.yaw())
            && close(rotation.getFloat(1), expected.pitch());
        if (!matches) throw new IOException("Saved owner differs from return point; recovery lease retained");
    }

    private static boolean close(double actual, double expected) {
        return Double.isFinite(actual) && Math.abs(actual - expected) <= 0.000001;
    }
    private static boolean sameYaw(float actual, float expected) {
        if (!Float.isFinite(actual)) return false;
        double difference = ((double) actual - expected) % 360;
        return Math.abs(difference) <= 0.0001 || Math.abs(Math.abs(difference) - 360) <= 0.0001;
    }
}
