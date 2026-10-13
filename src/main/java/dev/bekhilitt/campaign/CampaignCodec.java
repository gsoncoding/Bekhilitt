package dev.bekhilitt.campaign;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import static dev.bekhilitt.campaign.CampaignSnapshot.*;

/**
 * Dependency-free, deterministic ASCII key=value snapshot format (a strict
 * Properties subset). No escapes, comments, continuation lines, duplicate keys,
 * unknown fields, implicit defaults, partial loads, or automatic migrations.
 * Adapters may instead embed toFields/fromFields in their own validated envelope.
 * Decode failures leave persistence/recovery policy to the adapter: never overwrite
 * the source with a new campaign. This is not the future story-content JSON loader.
 */
public final class CampaignCodec {
    public static final int MAX_CHARACTERS = 32768;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "contentVersion", "campaignId",
            "participantId", "revision", "chapterId", "checkpoint", "checkpointNodeId", "sceneId",
            "runId", "attemptId", "status", "interruption", "completedEventIds", "flags", "appliedEffects");
    private CampaignCodec() { }

    public static String encode(CampaignSnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(toFields(snapshot)).forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.toString();
    }

    public static CampaignSnapshot decode(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_CHARACTERS)
            throw invalid("document", "empty, null, or too large");
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        if (lines.length > FIELDS.size() + 1) throw invalid("document", "too many lines");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty() && i == lines.length - 1) continue;
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('='))
                throw invalid("line " + (i + 1), "expected one key=value pair");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (values.putIfAbsent(key, value) != null) throw invalid(key, "duplicate field");
        }
        return fromFields(values);
    }

    public static Map<String, String> toFields(CampaignSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schemaVersion", Integer.toString(snapshot.schemaVersion()));
        values.put("contentVersion", snapshot.contentVersion());
        values.put("campaignId", snapshot.campaignId().toString());
        values.put("participantId", snapshot.participantId().toString());
        values.put("revision", Long.toString(snapshot.revision()));
        values.put("chapterId", snapshot.chapterId());
        values.put("checkpoint", snapshot.checkpoint().name());
        values.put("checkpointNodeId", snapshot.checkpointNodeId());
        values.put("sceneId", snapshot.sceneId());
        values.put("runId", snapshot.runId().toString());
        values.put("attemptId", snapshot.attemptId().toString());
        values.put("status", snapshot.status().name());
        values.put("interruption", snapshot.interruption().name());
        values.put("completedEventIds", join(snapshot.completedEventIds()));
        values.put("flags", join(snapshot.flags()));
        values.put("appliedEffects", join(snapshot.appliedEffects()));
        return Map.copyOf(values);
    }

    public static CampaignSnapshot fromFields(Map<String, String> fields) {
        if (fields == null || !FIELDS.equals(fields.keySet()))
            throw invalid("fields", "missing or unknown field");
        // Copy before validation: an externally held mutable map is never the saved state.
        Map<String, String> values = new LinkedHashMap<>(fields);
        long totalSize = 0;
        for (Map.Entry<String, String> field : values.entrySet()) {
            String value = field.getValue();
            if (value == null || !value.matches("[A-Za-z0-9_:|,/-]*"))
                throw invalid(field.getKey(), "invalid characters or null value");
            totalSize += field.getKey().length() + value.length() + 2L;
        }
        if (totalSize > MAX_CHARACTERS) throw invalid("fields", "too large");
        int schema = integer(values.get("schemaVersion"), "schemaVersion");
        Stage checkpoint = enumValue(Stage.class, values.get("checkpoint"), "checkpoint");
        if (!checkpoint.nodeId().equals(values.get("checkpointNodeId")))
            throw invalid("checkpointNodeId", "unknown or inconsistent checkpoint");
        if (!checkpoint.sceneId().equals(values.get("sceneId")))
            throw invalid("sceneId", "unknown or inconsistent scene");
        return new CampaignSnapshot(schema, values.get("contentVersion"), uuid(values.get("campaignId"), "campaignId"),
                uuid(values.get("participantId"), "participantId"), number(values.get("revision"), "revision"),
                values.get("chapterId"), checkpoint, uuid(values.get("runId"), "runId"), uuid(values.get("attemptId"), "attemptId"),
                enumValue(Status.class, values.get("status"), "status"),
                enumValue(Interruption.class, values.get("interruption"), "interruption"),
                split(values.get("completedEventIds"), "completedEventIds"), split(values.get("flags"), "flags"),
                split(values.get("appliedEffects"), "appliedEffects"));
    }

    private static String join(Set<String> values) { return String.join(",", new TreeSet<>(values)); }
    private static Set<String> split(String value, String field) {
        if (value.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            if (item.isEmpty() || !result.add(item)) throw invalid(field, "empty or duplicate entry");
        }
        return Set.copyOf(result);
    }
    private static long number(String value, String field) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid(field, "expected canonical nonnegative integer");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw invalid(field, "integer overflow"); }
    }
    private static int integer(String value, String field) {
        long parsed = number(value, field);
        if (parsed > Integer.MAX_VALUE) throw invalid(field, "integer overflow");
        return (int) parsed;
    }
    private static UUID uuid(String value, String field) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw invalid(field, "expected canonical UUID");
            return id;
        } catch (IllegalArgumentException error) { throw invalid(field, "invalid UUID"); }
    }
    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException error) { throw invalid(field, "unknown value " + value); }
    }
}
