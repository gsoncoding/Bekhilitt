package dev.bekhilitt.platform.persistence;

import dev.bekhilitt.platform.story.WarCampFoundation;
import dev.bekhilitt.platform.story.WarCampFoundation.Column;
import dev.bekhilitt.platform.story.WarCampPlan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Write-once terrain samples, stored as a strict canonical JSON document. */
public final class CampPlacementStore {
    public static final int MAX_BYTES = 131072;
    private static final Object WRITE_LOCK = new Object();
    private static final String NUMBER = "-?(?:0|[1-9][0-9]*)";
    private static final Pattern ENVELOPE = Pattern.compile("\\{\"format\":1,\"campaignId\":\"([0-9a-f-]{36})\",\"seed\":(" + NUMBER
        + "),\"floor\":(" + NUMBER + "),\"groundFirstAir\":\\[(.*)\\]\\}\\n?");
    private static final Pattern COLUMN = Pattern.compile("\\[(" + NUMBER + "),(" + NUMBER + "),(" + NUMBER + ")\\]");
    private final Path path;
    private boolean blocked;

    public CampPlacementStore(Path worldRoot) {
        path = worldRoot.resolve("data").resolve("bekhilitt-camp-placement.snapshot");
    }
    public Path path() { return path; }
    public boolean exists() { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }

    public record Saved(UUID campaignId, long seed, int floor, Map<Column, Integer> groundFirstAir) {
        public Saved {
            if (campaignId == null || groundFirstAir == null || groundFirstAir.isEmpty())
                throw new IllegalArgumentException("Missing placement identity or terrain");
            groundFirstAir = Collections.unmodifiableMap(new LinkedHashMap<>(groundFirstAir));
            int maximum = Integer.MIN_VALUE;
            for (var entry : groundFirstAir.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || Math.abs((long) entry.getValue()) > 20000)
                    throw new IllegalArgumentException("Invalid terrain sample");
                maximum = Math.max(maximum, entry.getValue());
            }
            if ((long) maximum + 6 != floor) throw new IllegalArgumentException("Placement floor does not match original terrain");
            WarCampFoundation.plan(WarCampPlan.layout(seed), floor, groundFirstAir);
        }
    }

    public Saved read() throws IOException {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES)
                throw new IOException("Missing, nonregular or oversized placement snapshot");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("Oversized placement snapshot");
            return decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            blocked = true;
            throw new IOException("Camp placement unreadable; left unchanged: " + path, error);
        }
    }

    public void write(Saved saved) throws IOException {
        synchronized (WRITE_LOCK) {
            if (blocked) throw new IOException("Placement writes blocked after failed read");
            if (saved == null) throw new IOException("Missing placement");
            if (exists()) { verifyIdentical(saved); return; }
            byte[] bytes = encode(saved).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Placement exceeds size limit");
            Files.createDirectories(path.getParent());
            // Cooperating writers across server processes also serialize initial publication.
            try (FileChannel lockChannel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = lockChannel.lock()) {
                if (exists()) { verifyIdentical(saved); return; }
                Path temp = Files.createTempFile(path.getParent(), "bekhilitt-camp-placement-", ".pending");
                try {
                    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.force(true);
                    }
                    if (exists()) { verifyIdentical(saved); return; }
                    // No non-atomic fallback and no requested replacement of existing snapshots.
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
                    verifyIdentical(saved);
                } finally { Files.deleteIfExists(temp); }
            }
        }
    }
    private void verifyIdentical(Saved saved) throws IOException {
        if (!read().equals(saved)) {
            blocked = true;
            throw new IOException("Refusing changed campaign, seed or camp placement: " + path);
        }
        // Also retry directory durability after a previous rename succeeded but
        // its directory force failed. An existing matching file is not enough.
        try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
    public static String encode(Saved saved) {
        StringBuilder out = new StringBuilder("{\"format\":1,\"campaignId\":\"").append(saved.campaignId())
            .append("\",\"seed\":").append(saved.seed()).append(",\"floor\":").append(saved.floor())
            .append(",\"groundFirstAir\":[");
        boolean first = true;
        for (int x = WarCampPlan.MIN_X; x <= WarCampPlan.MAX_X; x++)
            for (int z = WarCampPlan.MIN_Z; z <= WarCampPlan.MAX_Z; z++) {
                Integer y = saved.groundFirstAir().get(new Column(x, z));
                if (y == null) continue;
                if (!first) out.append(',');
                first = false;
                out.append('[').append(x).append(',').append(z).append(',').append(y).append(']');
            }
        return out.append("]}\n").toString();
    }
    public static Saved decode(String text) {
        if (text == null || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Missing or oversized placement");
        var envelope = ENVELOPE.matcher(text);
        if (!envelope.matches()) throw new IllegalArgumentException("Unsupported or malformed placement JSON");
        UUID campaign = UUID.fromString(envelope.group(1));
        if (!campaign.toString().equals(envelope.group(1))) throw new IllegalArgumentException("Noncanonical UUID");
        var columns = new LinkedHashMap<Column, Integer>();
        String body = envelope.group(4);
        int cursor = 0;
        while (cursor < body.length()) {
            var value = COLUMN.matcher(body).region(cursor, body.length());
            if (!value.lookingAt()) throw new IllegalArgumentException("Malformed terrain sample");
            Column column = new Column(Integer.parseInt(value.group(1)), Integer.parseInt(value.group(2)));
            if (columns.putIfAbsent(column, Integer.parseInt(value.group(3))) != null)
                throw new IllegalArgumentException("Duplicate terrain column");
            cursor = value.end();
            if (cursor < body.length()) {
                if (body.charAt(cursor++) != ',' || cursor == body.length()) throw new IllegalArgumentException("Malformed terrain delimiter");
            }
        }
        Saved saved = new Saved(campaign, Long.parseLong(envelope.group(2)), Integer.parseInt(envelope.group(3)), columns);
        String canonical = encode(saved);
        if (!text.equals(canonical) && !text.equals(canonical.substring(0, canonical.length() - 1)))
            throw new IllegalArgumentException("Noncanonical placement encoding");
        return saved;
    }
}
