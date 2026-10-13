package dev.bekhilitt.platform.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bekhilitt.campaign.CampaignSnapshot;
import dev.bekhilitt.combat.Duel;
import dev.bekhilitt.combat.EpicDuelAttempt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fixed, validated first-episode presentation data. JSON cannot execute effects or skip a duel. */
public final class EpisodeContent {
    public static final String RESOURCE = "/data/bekhilitt/story/first_episode.json";
    public record Phase(String id, int durationTicks, String speaker, String body, String objective, List<String> pages) {
        public Phase { pages = List.copyOf(pages); }
    }
    private final Map<String, Phase> phases;
    private EpisodeContent(Map<String, Phase> phases) { this.phases = Map.copyOf(phases); }
    public Phase phase(CampaignSnapshot.Stage stage) { return phases.get(stage.name().toLowerCase(Locale.ROOT)); }
    public Phase phase(CampaignSnapshot.Stage stage, Duel.Phase duelPhase) {
        Phase base = phase(stage);
        if (stage != CampaignSnapshot.Stage.DUEL || duelPhase == null) return base;
        String cue = switch (duelPhase) {
            case WARNING -> "warning"; case FINISH -> "finish"; case RESULT -> "result"; default -> null;
        };
        if (cue == null) return base;
        String prefix = "story.bekhilitt.duel." + cue;
        return new Phase(base.id(), base.durationTicks(), base.speaker(), prefix + ".body", prefix + ".objective", base.pages());
    }
    public Phase epicPhase(CampaignSnapshot.Stage stage, EpicDuelAttempt.Phase duelPhase) {
        Phase base = phase(stage);
        if (stage != CampaignSnapshot.Stage.DUEL || duelPhase == null) return base;
        String cue = switch (duelPhase) {
            case WARNING -> "warning"; case FINISH -> "finish"; case RESULT -> "result"; default -> null;
        };
        if (cue == null) return base;
        String prefix = "story.bekhilitt.duel." + cue;
        return new Phase(base.id(), base.durationTicks(), base.speaker(), prefix + ".body", prefix + ".objective", base.pages());
    }
    public static EpisodeContent load() throws IOException {
        try {
            return parse(read(RESOURCE), read("/assets/bekhilitt/lang/ru_ru.json"), read("/assets/bekhilitt/lang/en_us.json"));
        } catch (RuntimeException error) { throw new IOException(RESOURCE + ": " + error.getMessage(), error); }
    }
    static EpisodeContent parse(JsonObject root, JsonObject ru, JsonObject en) {
            fields(root, Set.of("schemaVersion", "contentVersion", "id", "adaptation", "phases"), RESOURCE);
            if (integer(root, "schemaVersion") != 1 || !string(root, "contentVersion").equals("first_episode_1")
                    || !string(root, "id").equals("bekhilitt:golden_age/first_episode")
                    || !string(root, "adaptation").equals("paraphrase_only"))
                throw new IllegalArgumentException("Unsupported story identity/version/adaptation");
            if (!root.get("phases").isJsonArray()) throw new IllegalArgumentException("phases must be an array");
            for (String cue : List.of("warning", "finish", "result")) {
                key("story.bekhilitt.duel." + cue + ".body", ru, en);
                key("story.bekhilitt.duel." + cue + ".objective", ru, en);
            }
            Map<String, Phase> phases = new LinkedHashMap<>();
            for (JsonElement value : root.getAsJsonArray("phases")) {
                JsonObject obj = value.getAsJsonObject();
                fields(obj, Set.of("id", "durationTicks", "speaker", "body", "objective", "pages"), "phases[]");
                String id = string(obj, "id");
                int duration = integer(obj, "durationTicks");
                if (duration < 0 || duration > 400) throw new IllegalArgumentException(id + ".durationTicks out of range");
                List<String> pages = new ArrayList<>();
                if (!obj.get("pages").isJsonArray()) throw new IllegalArgumentException(id + ".pages must be array");
                for (JsonElement page : obj.getAsJsonArray("pages")) pages.add(key(page.getAsString(), ru, en));
                if (pages.size() > 8) throw new IllegalArgumentException(id + ": too many dialogue pages");
                Phase phase = new Phase(id, duration, key(string(obj, "speaker"), ru, en), key(string(obj, "body"), ru, en),
                    key(string(obj, "objective"), ru, en), pages);
                if (phases.putIfAbsent(id, phase) != null) throw new IllegalArgumentException("Duplicate phase " + id);
            }
            Set<String> expected = Set.of("injury", "transport", "recovery", "awakening", "casca_dialogue", "griffith_dialogue", "duel", "end");
            if (!phases.keySet().equals(expected)) throw new IllegalArgumentException("Missing/unknown first-episode phase");
            for (Phase phase : phases.values()) {
                boolean dialogue = phase.id().endsWith("_dialogue");
                if (dialogue != !phase.pages().isEmpty()) throw new IllegalArgumentException(phase.id() + ": dialogue pages mismatch");
                if ((dialogue || phase.id().equals("duel")) != (phase.durationTicks() == 0))
                    throw new IllegalArgumentException(phase.id() + ": unsafe automatic transition duration");
            }
            return new EpisodeContent(phases);
    }
    private static JsonObject read(String resource) throws IOException {
        try (InputStream in = EpisodeContent.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing resource " + resource);
            byte[] bytes = in.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Oversized resource " + resource);
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static void fields(JsonObject obj, Set<String> expected, String path) {
        if (!obj.keySet().equals(expected)) throw new IllegalArgumentException(path + ": missing/unknown fields");
    }
    private static String string(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }
    private static int integer(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException(key + " must be a positive integer");
        return value.getAsInt();
    }
    private static String key(String value, JsonObject ru, JsonObject en) {
        if (!value.matches("story\\.bekhilitt\\.[a-z0-9_.]+")) throw new IllegalArgumentException("Invalid translation key " + value);
        for (JsonObject lang : List.of(ru, en)) {
            String text = string(lang, value);
            if (text.isBlank() || text.length() > 1024) throw new IllegalArgumentException("Invalid translation for " + value);
        }
        return value;
    }
}
