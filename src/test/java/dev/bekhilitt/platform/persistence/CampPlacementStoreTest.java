package dev.bekhilitt.platform.persistence;

import dev.bekhilitt.platform.story.WarCampFoundation;
import dev.bekhilitt.platform.story.WarCampPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CampPlacementStoreTest {
    @TempDir Path world;
    private final UUID campaign = UUID.randomUUID();
    private CampPlacementStore.Saved saved(long seed, int surface) {
        Map<WarCampFoundation.Column, Integer> terrain = new LinkedHashMap<>();
        for (var cell : WarCampPlan.layout(seed).keySet()) terrain.put(new WarCampFoundation.Column(cell.x(), cell.z()), surface);
        return new CampPlacementStore.Saved(campaign, seed, surface + 6, terrain);
    }
    @Test void deterministicRoundTripAndImmutableTerrain() {
        var original = saved(-517L, 74);
        assertEquals(original, CampPlacementStore.decode(CampPlacementStore.encode(original)));
        assertEquals(CampPlacementStore.encode(original), CampPlacementStore.encode(CampPlacementStore.decode(CampPlacementStore.encode(original))));
        assertThrows(UnsupportedOperationException.class, () -> original.groundFirstAir().clear());
        assertTrue(CampPlacementStore.encode(original).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < CampPlacementStore.MAX_BYTES);
    }
    @Test void atomicWriteAndIdenticalRepeatPreserveExactPlacement() throws IOException {
        var store = new CampPlacementStore(world); var original = saved(18, 63);
        store.write(original); assertEquals(original, store.read());
        byte[] bytes = Files.readAllBytes(store.path());
        store.write(original); assertArrayEquals(bytes, Files.readAllBytes(store.path()));
        try (var files = Files.list(store.path().getParent())) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".pending")));
        }
    }
    @Test void existingPlacementCannotBeRaisedOrChanged() throws IOException {
        var store = new CampPlacementStore(world); var original = saved(18, 63); store.write(original);
        assertThrows(IOException.class, () -> store.write(saved(18, 80)));
        assertThrows(IOException.class, () -> store.write(saved(19, 63)));
        var other = new CampPlacementStore.Saved(UUID.randomUUID(), original.seed(), original.floor(), original.groundFirstAir());
        assertThrows(IOException.class, () -> store.write(other));
        assertEquals(original, store.read());
    }
    @Test void corruptAndFutureFilesFailClosedWithoutOverwrite() throws IOException {
        for (String invalid : new String[]{"broken", CampPlacementStore.encode(saved(3, 70)).replace("\"format\":1", "\"format\":99")}) {
            var store = new CampPlacementStore(world);
            Files.createDirectories(store.path().getParent()); Files.writeString(store.path(), invalid);
            assertThrows(IOException.class, store::read);
            assertThrows(IOException.class, () -> store.write(saved(3, 70)));
            assertEquals(invalid, Files.readString(store.path()));
        }
    }
    @Test void malformedUnknownDuplicateAndMissingFieldsReject() {
        String valid = CampPlacementStore.encode(saved(3, 70));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode(valid.replace("\"seed\":3", "\"extra\":0,\"seed\":3")));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode(valid.replace("\"seed\":3", "\"seed\":3,\"seed\":3")));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode(valid.replace(",\"floor\":76", "")));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode(valid.replace("\"seed\":3", "\"seed\":03")));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode(valid.replace("\"floor\":76", "\"floor\":77")));
    }
    @Test void terrainSetAndFoundationBudgetAreValidatedBeforeWriting() {
        var original = saved(8, 63); var map = new LinkedHashMap<>(original.groundFirstAir());
        map.remove(new WarCampFoundation.Column(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CampPlacementStore.Saved(campaign, 8, 69, map));
        map.put(new WarCampFoundation.Column(0, 0), -100);
        assertThrows(IllegalArgumentException.class, () -> new CampPlacementStore.Saved(campaign, 8, 69, map));
    }
    @Test void duplicateAndOversizedTerrainEncodingReject() {
        String valid = CampPlacementStore.encode(saved(8, 63));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode(valid.replace("]]}\n", "],[0,0,63]]}\n")));
        assertThrows(IllegalArgumentException.class, () -> CampPlacementStore.decode("x".repeat(CampPlacementStore.MAX_BYTES + 1)));
    }
}
