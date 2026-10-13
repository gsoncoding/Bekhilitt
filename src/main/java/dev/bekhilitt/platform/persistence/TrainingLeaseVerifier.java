package dev.bekhilitt.platform.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Confirms the precise recovery snapshot exists before lending any training state. */
public final class TrainingLeaseVerifier {
    public static final String LEASE_KEY = "bekhilitt_epic_training_v1";
    public static final String PRACTICE_KEY = "bekhilitt_epic_skill_practice_v1";
    private static final long MAX_COMPRESSED_BYTES = 16L * 1024 * 1024;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private TrainingLeaseVerifier() { }

    public static void validate(UUID owner, CompoundTag lease) {
        if (!lease.getAllKeys().equals(Set.of("owner", "attempt", "addedGuard", "addedDodge", "guardWasLearned", "dodgeWasLearned",
                "mode", "health", "stamina", "food", "saturation", "exhaustion")))
            throw new IllegalArgumentException("Training lease fields are missing or unknown");
        for (String key : new String[]{"owner", "attempt", "mode"})
            if (!lease.contains(key, 8)) throw new IllegalArgumentException("Training lease string field is malformed: " + key);
        if (!owner.equals(UUID.fromString(lease.getString("owner")))) throw new IllegalArgumentException("Training lease owner differs");
        UUID.fromString(lease.getString("attempt"));
        if (!Set.of("VANILLA", "EPICFIGHT").contains(lease.getString("mode"))) throw new IllegalArgumentException("Unknown training mode");
        for (String key : new String[]{"addedGuard", "addedDodge", "guardWasLearned", "dodgeWasLearned"})
            if (!lease.contains(key, 1) || (lease.getByte(key) != 0 && lease.getByte(key) != 1))
                throw new IllegalArgumentException("Training lease boolean is malformed: " + key);
        for (String key : new String[]{"health", "stamina", "saturation", "exhaustion"})
            if (!lease.contains(key, 5) || !Float.isFinite(lease.getFloat(key)) || lease.getFloat(key) < 0)
                throw new IllegalArgumentException("Training lease number is malformed: " + key);
        if (lease.getFloat("health") <= 0 || !lease.contains("food", 3) || lease.getInt("food") < 0 || lease.getInt("food") > 20)
            throw new IllegalArgumentException("Training lease health/food out of bounds");
    }

    public static void validatePractice(UUID owner, CompoundTag lease) {
        if (!lease.getAllKeys().equals(Set.of("format", "owner", "attempt", "leapWasLearned", "slamWasLearned", "mobGriefing")))
            throw new IllegalArgumentException("Practice lease fields are missing or unknown");
        if (!lease.contains("format", 3) || lease.getInt("format") != 1)
            throw new IllegalArgumentException("Unsupported practice lease format");
        for (String key : new String[]{"owner", "attempt"}) {
            if (!lease.contains(key, 8)) throw new IllegalArgumentException("Malformed practice identity");
            UUID id = UUID.fromString(lease.getString(key));
            if (!id.toString().equals(lease.getString(key))) throw new IllegalArgumentException("Noncanonical practice identity");
        }
        if (!owner.toString().equals(lease.getString("owner"))) throw new IllegalArgumentException("Practice lease owner differs");
        for (String key : new String[]{"leapWasLearned", "slamWasLearned", "mobGriefing"})
            if (!lease.contains(key, 1) || (lease.getByte(key) != 0 && lease.getByte(key) != 1))
                throw new IllegalArgumentException("Malformed practice boolean: " + key);
    }

    public static void verify(Path world, UUID owner, boolean dedicated, CompoundTag expected) throws IOException {
        verifyLease(world, owner, dedicated, LEASE_KEY, expected);
    }

    public static void verifyPractice(Path world, UUID owner, CompoundTag expected) throws IOException {
        validatePractice(owner, expected);
        verifyLease(world, owner, false, PRACTICE_KEY, expected);
    }

    public static void verifyCamp(Path world, UUID owner, CompoundTag expected, boolean restored) throws IOException {
        dev.bekhilitt.platform.CampPlayerLease.validate(owner, expected);
        verifyLease(world, owner, false, dev.bekhilitt.platform.CampPlayerLease.KEY, expected);
        if (restored) {
            CompoundTag player = readSource(world.resolve("level.dat")).getCompound("Data").getCompound("Player");
            if (!player.contains("Health",5) || !player.contains("foodLevel",3)
                    || !player.contains("foodSaturationLevel",5) || !player.contains("foodExhaustionLevel",5)
                    || Float.compare(player.getFloat("Health"), expected.getFloat("health")) != 0
                    || player.getInt("foodLevel") != expected.getInt("food")
                    || Float.compare(player.getFloat("foodSaturationLevel"), expected.getFloat("saturation")) != 0
                    || Float.compare(player.getFloat("foodExhaustionLevel"), expected.getFloat("exhaustion")) != 0)
                throw new IOException("Camp vitals were not durably restored; lease retained");
        }
    }

    /** Rule restoration is saved while the recovery lease still exists, then read back. */
    public static void verifyPracticeRuleRestored(Path world, UUID owner, CompoundTag expected) throws IOException {
        verifyPractice(world, owner, expected);
        CompoundTag root = readSource(world.resolve("level.dat"));
        if (!root.contains("Data", 10) || !root.getCompound("Data").contains("GameRules", 10))
            throw new IOException("Practice game rules save is missing");
        CompoundTag rules = root.getCompound("Data").getCompound("GameRules");
        if (!rules.contains("mobGriefing", 8)
                || !Boolean.toString(expected.getBoolean("mobGriefing")).equals(rules.getString("mobGriefing")))
            throw new IOException("Original mobGriefing was not durably restored; practice lease retained");
    }

    private static CompoundTag readSource(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_COMPRESSED_BYTES)
            throw new IOException("Training recovery save is missing or oversized");
        return NbtIo.readCompressed(source, NbtAccounter.create(MAX_NBT_BYTES));
    }

    private static void verifyLease(Path world, UUID owner, boolean dedicated, String key, CompoundTag expected) throws IOException {
        Path source = dedicated ? world.resolve("playerdata").resolve(owner + ".dat") : world.resolve("level.dat");
        try {
            CompoundTag root = readSource(source);
            CompoundTag player;
            if (dedicated) player = root;
            else {
                if (!root.contains("Data", 10) || !root.getCompound("Data").contains("Player", 10))
                    throw new IOException("Integrated owner save is missing");
                player = root.getCompound("Data").getCompound("Player");
            }
            if (!player.hasUUID("UUID") || !owner.equals(player.getUUID("UUID")))
                throw new IOException("Training recovery owner differs");
            if (!player.contains("NeoForgeData", 10) || !player.getCompound("NeoForgeData").contains(key, 10)
                    || !expected.equals(player.getCompound("NeoForgeData").getCompound(key)))
                throw new IOException("Training recovery lease was not durably saved; equipment was not issued");
        } catch (RuntimeException error) {
            throw new IOException("Training recovery save cannot be verified", error);
        }
    }
}
