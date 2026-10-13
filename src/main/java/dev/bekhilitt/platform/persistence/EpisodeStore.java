package dev.bekhilitt.platform.persistence;

import dev.bekhilitt.campaign.CampaignCodec;
import dev.bekhilitt.campaign.CampaignSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One server-owned, versioned world snapshot. Invalid existing data is never reset. */
public final class EpisodeStore {
    public static final int MAX_BYTES = 131072;
    private static final Set<String> FIELDS = Set.of("format", "campaign", "returnOwner", "returnDimension",
        "returnX", "returnY", "returnZ", "returnYaw", "returnPitch", "returnMode", "returnPending");
    private final Path path;
    private boolean blocked;
    private Saved lastRead;

    public EpisodeStore(Path worldRoot) { path = worldRoot.resolve("data").resolve("bekhilitt-first-episode.snapshot"); }
    public Path path() { return path; }
    public boolean exists() { return Files.exists(path); }
    public record ReturnPoint(UUID owner, String dimension, double x, double y, double z,
                              float yaw, float pitch, String mode, boolean pending) {
        public ReturnPoint {
            if (owner == null || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid return owner/dimension");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(x) > 30_000_000
                    || Math.abs(z) > 30_000_000 || Math.abs(y) > 20_000 || Math.abs(pitch) > 90)
                throw new IllegalArgumentException("Invalid return coordinates");
            if (!Set.of("survival", "creative", "adventure", "spectator").contains(mode))
                throw new IllegalArgumentException("Invalid return game mode");
        }
        public ReturnPoint withPending(boolean value) { return new ReturnPoint(owner, dimension, x, y, z, yaw, pitch, mode, value); }
    }
    public record Saved(CampaignSnapshot campaign, ReturnPoint returnPoint) {
        public Saved {
            if (campaign == null || returnPoint == null || !campaign.participantId().equals(returnPoint.owner()))
                throw new IllegalArgumentException("Campaign and return owner differ");
        }
    }
    public Saved read() throws IOException {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) throw new IOException("Missing/oversized snapshot: " + path);
            lastRead = decode(Files.readString(path, StandardCharsets.UTF_8));
            return lastRead;
        } catch (IOException | RuntimeException error) {
            blocked = true;
            throw new IOException("Campaign snapshot is unreadable; left unchanged: " + path + ": " + error.getMessage(), error);
        }
    }
    public void write(Saved saved) throws IOException {
        if (blocked) throw new IOException("Snapshot writes blocked after failed read: " + path);
        if (Files.exists(path)) read();
        if (lastRead != null && (!lastRead.campaign().campaignId().equals(saved.campaign().campaignId())
                || !lastRead.campaign().participantId().equals(saved.campaign().participantId())
                || saved.campaign().revision() < lastRead.campaign().revision()
                || (saved.campaign().revision() == lastRead.campaign().revision() && !saved.campaign().equals(lastRead.campaign()))))
            throw new IOException("Refusing different owner/campaign or stale revision: " + path);
        byte[] bytes = encode(saved).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Snapshot exceeds size limit");
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "bekhilitt-first-episode-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: a filesystem without atomic replacement must fail safely.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            lastRead = saved;
        } finally { Files.deleteIfExists(temp); }
    }
    public static String encode(Saved saved) {
        ReturnPoint r = saved.returnPoint();
        var fields = new LinkedHashMap<String, String>();
        fields.put("format", "1");
        fields.put("campaign", Base64.getEncoder().encodeToString(CampaignCodec.encode(saved.campaign()).getBytes(StandardCharsets.UTF_8)));
        fields.put("returnOwner", r.owner().toString()); fields.put("returnDimension", r.dimension());
        fields.put("returnX", Double.toString(r.x())); fields.put("returnY", Double.toString(r.y())); fields.put("returnZ", Double.toString(r.z()));
        fields.put("returnYaw", Float.toString(r.yaw())); fields.put("returnPitch", Float.toString(r.pitch()));
        fields.put("returnMode", r.mode()); fields.put("returnPending", Boolean.toString(r.pending()));
        StringBuilder out = new StringBuilder(); fields.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return out.toString();
    }
    public static Saved decode(String text) {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Oversized snapshot");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\\n", -1)) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Malformed envelope line");
            String key = line.substring(0, separator), value = line.substring(separator + 1);
            if (!FIELDS.contains(key) || fields.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Unknown/duplicate envelope field: " + key);
        }
        if (!fields.keySet().equals(FIELDS) || !"1".equals(fields.get("format")))
            throw new IllegalArgumentException("Unsupported/incomplete envelope format");
        String pending = fields.get("returnPending");
        if (!pending.equals("true") && !pending.equals("false")) throw new IllegalArgumentException("Invalid returnPending");
        CampaignSnapshot snapshot = CampaignCodec.decode(new String(Base64.getDecoder().decode(fields.get("campaign")), StandardCharsets.UTF_8));
        ReturnPoint point = new ReturnPoint(UUID.fromString(fields.get("returnOwner")), fields.get("returnDimension"),
            Double.parseDouble(fields.get("returnX")), Double.parseDouble(fields.get("returnY")), Double.parseDouble(fields.get("returnZ")),
            Float.parseFloat(fields.get("returnYaw")), Float.parseFloat(fields.get("returnPitch")), fields.get("returnMode"), Boolean.parseBoolean(pending));
        return new Saved(snapshot, point);
    }
}
