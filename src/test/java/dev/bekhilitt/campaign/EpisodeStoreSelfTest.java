package dev.bekhilitt.campaign;

import dev.bekhilitt.platform.persistence.EpisodeStore;
import dev.bekhilitt.platform.persistence.EpisodeStore.ReturnPoint;
import dev.bekhilitt.platform.persistence.EpisodeStore.Saved;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Dependency-free tests for the platform's pure Java persistence envelope. */
public final class EpisodeStoreSelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    @FunctionalInterface interface InDirectory { void run(Path root) throws Exception; }
    private EpisodeStoreSelfTest() { }
    public static void main(String[] args) throws Exception { runAll(); }
    public static void runAll() throws Exception {
        var cases = new LinkedHashMap<String, Checked>();
        cases.put("return location and snapshot round trip", EpisodeStoreSelfTest::roundTrip);
        cases.put("atomic file replacement and temporary cleanup", EpisodeStoreSelfTest::atomicFileRoundTrip);
        cases.put("corrupt existing file is never reset", EpisodeStoreSelfTest::corruptFilePreserved);
        cases.put("future schema file is never reset", EpisodeStoreSelfTest::futureFilePreserved);
        cases.put("oversized file blocks later writes", EpisodeStoreSelfTest::oversizedFilePreserved);
        cases.put("owner and campaign replacement rejected", EpisodeStoreSelfTest::foreignOwnerRejected);
        cases.put("stale revision rejected", EpisodeStoreSelfTest::staleRevisionRejected);
        cases.put("same revision cannot contain different campaign state", EpisodeStoreSelfTest::conflictingRevisionRejected);
        cases.put("return delivery flag can change without campaign revision", EpisodeStoreSelfTest::returnPendingChange);
        cases.put("cached store detects a newer disk revision", EpisodeStoreSelfTest::cachedRevisionRejected);
        cases.put("cached store preserves newly corrupted disk data", EpisodeStoreSelfTest::cachedCorruptionPreserved);
        cases.put("invalid return locations rejected", EpisodeStoreSelfTest::invalidReturnPoints);
        cases.put("malformed envelope rejected", EpisodeStoreSelfTest::malformedEnvelope);
        int failed = 0;
        for (var test : cases.entrySet()) {
            try { test.getValue().run(); System.out.println("PASS " + test.getKey()); }
            catch (Throwable failure) { failed++; System.out.println("FAIL " + test.getKey() + ": " + failure); }
        }
        System.out.println("Episode store self-test: " + (cases.size() - failed) + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("Persistence regressions failed: " + failed);
    }

    static void roundTrip() {
        CampaignSnapshot snapshot = fresh().snapshot();
        for (String mode : new String[] {"survival", "creative", "adventure", "spectator"}) {
            for (boolean pending : new boolean[] {false, true}) {
                Saved saved = new Saved(snapshot, new ReturnPoint(snapshot.participantId(), "minecraft:the_nether",
                        -123.75, -63.0, 999.125, -180.5f, 89.5f, mode, pending));
                equal(saved, EpisodeStore.decode(EpisodeStore.encode(saved)));
            }
        }
    }

    static void atomicFileRoundTrip() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            CampaignService service = fresh();
            Saved original = saved(service.snapshot());
            store.write(original);
            equal(original, new EpisodeStore(root).read());
            for (int i = 0; i < 6; i++) {
                advance(service);
                Saved next = saved(service.snapshot());
                store.write(next);
                equal(next, new EpisodeStore(root).read());
            }
            try (var files = Files.list(store.path().getParent())) { equal(1L, files.count()); }
        });
    }

    static void corruptFilePreserved() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            Files.createDirectories(store.path().getParent());
            byte[] invalid = "this is not a campaign\n".getBytes(StandardCharsets.UTF_8);
            Files.write(store.path(), invalid);
            fails(IOException.class, () -> store.write(saved(fresh().snapshot())));
            equalBytes(invalid, Files.readAllBytes(store.path()));
            fails(IOException.class, () -> store.write(saved(fresh().snapshot())));
            equalBytes(invalid, Files.readAllBytes(store.path()));
        });
    }

    static void futureFilePreserved() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            CampaignSnapshot snapshot = fresh().snapshot();
            String futureCampaign = CampaignCodec.encode(snapshot).replace("schemaVersion=1", "schemaVersion=2");
            String encoded = EpisodeStore.encode(saved(snapshot));
            String future = encoded.replace(java.util.Base64.getEncoder().encodeToString(CampaignCodec.encode(snapshot).getBytes(StandardCharsets.UTF_8)),
                    java.util.Base64.getEncoder().encodeToString(futureCampaign.getBytes(StandardCharsets.UTF_8)));
            Files.createDirectories(store.path().getParent());
            Files.writeString(store.path(), future);
            fails(IOException.class, () -> store.read());
            fails(IOException.class, () -> store.write(saved(snapshot)));
            equal(future, Files.readString(store.path()));
        });
    }

    static void oversizedFilePreserved() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            Files.createDirectories(store.path().getParent());
            byte[] bytes = new byte[EpisodeStore.MAX_BYTES + 1];
            java.util.Arrays.fill(bytes, (byte) 'x');
            Files.write(store.path(), bytes);
            fails(IOException.class, () -> store.read());
            fails(IOException.class, () -> store.write(saved(fresh().snapshot())));
            equalBytes(bytes, Files.readAllBytes(store.path()));
        });
    }

    static void foreignOwnerRejected() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            CampaignSnapshot original = fresh().snapshot();
            store.write(saved(original));
            byte[] before = Files.readAllBytes(store.path());
            fails(IOException.class, () -> store.write(saved(CampaignService.create(UUID.randomUUID(), original.participantId()).snapshot())));
            fails(IOException.class, () -> store.write(saved(CampaignService.create(original.campaignId(), UUID.randomUUID()).snapshot())));
            equalBytes(before, Files.readAllBytes(store.path()));
        });
    }

    static void staleRevisionRejected() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            CampaignService service = fresh();
            Saved first = saved(service.snapshot());
            store.write(first);
            advance(service); store.write(saved(service.snapshot()));
            byte[] before = Files.readAllBytes(store.path());
            fails(IOException.class, () -> store.write(first));
            equalBytes(before, Files.readAllBytes(store.path()));
        });
    }

    static void conflictingRevisionRejected() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            CampaignSnapshot first = fresh().snapshot();
            store.write(saved(first));
            var fields = new HashMap<>(CampaignCodec.toFields(first));
            fields.put("attemptId", UUID.randomUUID().toString());
            CampaignSnapshot conflicting = CampaignCodec.fromFields(fields);
            byte[] before = Files.readAllBytes(store.path());
            fails(IOException.class, () -> store.write(saved(conflicting)));
            equalBytes(before, Files.readAllBytes(store.path()));
        });
    }

    static void returnPendingChange() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            Saved first = saved(fresh().snapshot());
            store.write(first);
            Saved returned = new Saved(first.campaign(), first.returnPoint().withPending(false));
            store.write(returned);
            equal(returned, new EpisodeStore(root).read());
            store.write(returned);
            equal(returned, new EpisodeStore(root).read());
        });
    }

    static void cachedRevisionRejected() throws Exception {
        inDirectory(root -> {
            EpisodeStore firstStore = new EpisodeStore(root);
            EpisodeStore secondStore = new EpisodeStore(root);
            CampaignService service = fresh();
            Saved first = saved(service.snapshot());
            firstStore.write(first); secondStore.read();
            advance(service); secondStore.write(saved(service.snapshot()));
            byte[] newer = Files.readAllBytes(firstStore.path());
            fails(IOException.class, () -> firstStore.write(first));
            equalBytes(newer, Files.readAllBytes(firstStore.path()));
        });
    }

    static void cachedCorruptionPreserved() throws Exception {
        inDirectory(root -> {
            EpisodeStore store = new EpisodeStore(root);
            CampaignService service = fresh();
            store.write(saved(service.snapshot()));
            byte[] corrupted = "damaged after the initial read\n".getBytes(StandardCharsets.UTF_8);
            Files.write(store.path(), corrupted);
            advance(service);
            fails(IOException.class, () -> store.write(saved(service.snapshot())));
            equalBytes(corrupted, Files.readAllBytes(store.path()));
        });
    }

    static void invalidReturnPoints() throws Exception {
        UUID owner = UUID.randomUUID();
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 30_000_001})
            fails(IllegalArgumentException.class, () -> new ReturnPoint(owner, "minecraft:overworld", value, 64, 0, 0, 0, "survival", true));
        fails(IllegalArgumentException.class, () -> new ReturnPoint(owner, "not_dimension", 0, 64, 0, 0, 0, "survival", true));
        fails(IllegalArgumentException.class, () -> new ReturnPoint(owner, "minecraft:overworld", 0, 64, 0, 0, 91, "survival", true));
        fails(IllegalArgumentException.class, () -> new ReturnPoint(owner, "minecraft:overworld", 0, 64, 0, 0, 0, "operator", true));
        fails(IllegalArgumentException.class, () -> new Saved(fresh().snapshot(), new ReturnPoint(owner, "minecraft:overworld", 0, 64, 0, 0, 0, "survival", true)));
    }

    static void malformedEnvelope() throws Exception {
        String valid = EpisodeStore.encode(saved(fresh().snapshot()));
        for (String invalid : new String[] {valid + "format=1\n", valid + "unknown=yes\n", valid.replace("format=1", "format=2"),
                valid.replace("returnPending=true", "returnPending=1"), valid.replace("returnX=12.5", "returnX=NaN"),
                valid.replace("returnMode=survival\n", ""), "campaign=not-base64\n", ""})
            fails(IllegalArgumentException.class, () -> EpisodeStore.decode(invalid));
    }

    private static CampaignService fresh() { return CampaignService.create(UUID.randomUUID(), UUID.randomUUID()); }
    private static Saved saved(CampaignSnapshot snapshot) {
        return new Saved(snapshot, new ReturnPoint(snapshot.participantId(), "minecraft:overworld", 12.5, 75, -30.25, 90, -15, "survival", true));
    }
    private static void advance(CampaignService service) {
        CampaignSnapshot snapshot = service.snapshot();
        equal(CampaignService.Result.APPLIED, service.advance(snapshot.participantId(), snapshot.attemptId(), snapshot.revision(), snapshot.stage()));
    }
    private static void inDirectory(InDirectory action) throws Exception {
        Path root = Files.createTempDirectory("bekhilitt-store-self-test-");
        try { action.run(root); }
        finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void equalBytes(byte[] expected, byte[] actual) {
        if (!java.util.Arrays.equals(expected, actual)) throw new AssertionError("Persisted bytes changed unexpectedly");
    }
    private static void fails(Class<? extends Throwable> expected, Checked action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " but operation succeeded");
    }
}
