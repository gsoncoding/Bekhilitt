package dev.bekhilitt.platform.story;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bekhilitt.campaign.CampaignSnapshot.Stage;
import dev.bekhilitt.combat.Duel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EpisodeContentTest {
    private JsonObject resource(String path) throws IOException {
        try (var in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private JsonObject root() throws IOException { return resource(EpisodeContent.RESOURCE); }
    private JsonObject ru() throws IOException { return resource("/assets/bekhilitt/lang/ru_ru.json"); }
    private JsonObject en() throws IOException { return resource("/assets/bekhilitt/lang/en_us.json"); }
    private void rejected(JsonObject root) throws IOException {
        JsonObject ru = ru(), en = en();
        assertThrows(IllegalArgumentException.class, () -> EpisodeContent.parse(root, ru, en));
    }
    @Test void packagedContentCoversEveryFixedStage() throws IOException {
        EpisodeContent content = EpisodeContent.load();
        for (Stage stage : Stage.values()) assertEquals(stage.nodeId(), content.phase(stage).id());
        assertEquals(3, content.phase(Stage.CASCA_DIALOGUE).pages().size());
        assertEquals(2, content.phase(Stage.GRIFFITH_DIALOGUE).pages().size());
        assertEquals(0, content.phase(Stage.DUEL).durationTicks());
    }
    @Test void stagedDuelNeverShowsFightInstructions() throws IOException {
        EpisodeContent content = EpisodeContent.load();
        for (Duel.Phase phase : new Duel.Phase[]{Duel.Phase.WARNING, Duel.Phase.FINISH, Duel.Phase.RESULT}) {
            assertNotEquals(content.phase(Stage.DUEL).body(), content.phase(Stage.DUEL, phase).body());
            assertNotEquals(content.phase(Stage.DUEL).objective(), content.phase(Stage.DUEL, phase).objective());
        }
    }
    @Test void missingStagingTranslationRejected() throws IOException {
        JsonObject root = root(), ru = ru(), en = en(); en.remove("story.bekhilitt.duel.finish.body");
        assertThrows(IllegalArgumentException.class, () -> EpisodeContent.parse(root, ru, en));
    }
    @Test void futureSchemaRejected() throws IOException { JsonObject root = root(); root.addProperty("schemaVersion", 2); rejected(root); }
    @Test void futureContentRejected() throws IOException { JsonObject root = root(); root.addProperty("contentVersion", "future"); rejected(root); }
    @Test void unknownFieldsRejected() throws IOException { JsonObject root = root(); root.addProperty("nextMission", "camp_intro"); rejected(root); }
    @Test void claimingVerifiedQuotesRejected() throws IOException { JsonObject root = root(); root.addProperty("adaptation", "verified_quote"); rejected(root); }
    @Test void missingStageRejected() throws IOException { JsonObject root = root(); root.getAsJsonArray("phases").remove(0); rejected(root); }
    @Test void duplicateStageRejected() throws IOException { JsonObject root = root(); root.getAsJsonArray("phases").add(root.getAsJsonArray("phases").get(0).deepCopy()); rejected(root); }
    @Test void automaticDuelCompletionRejected() throws IOException { JsonObject root = root(); root.getAsJsonArray("phases").get(6).getAsJsonObject().addProperty("durationTicks", 20); rejected(root); }
    @Test void nonDialogueCannotHavePages() throws IOException {
        JsonObject root = root(); root.getAsJsonArray("phases").get(0).getAsJsonObject().getAsJsonArray("pages").add("story.bekhilitt.injury.body"); rejected(root);
    }
    @Test void dialogueCannotLosePages() throws IOException {
        JsonObject root = root(); root.getAsJsonArray("phases").get(4).getAsJsonObject().getAsJsonArray("pages").asList().clear(); rejected(root);
    }
    @Test void zeroLengthIntroRejected() throws IOException { JsonObject root = root(); root.getAsJsonArray("phases").get(0).getAsJsonObject().addProperty("durationTicks", 0); rejected(root); }
    @Test void fractionalDurationRejected() throws IOException { JsonObject root = root(); root.getAsJsonArray("phases").get(0).getAsJsonObject().addProperty("durationTicks", 2.5); rejected(root); }
    @Test void unboundedSceneRejected() throws IOException { JsonObject root = root(); root.getAsJsonArray("phases").get(0).getAsJsonObject().addProperty("durationTicks", 10000); rejected(root); }
    @Test void missingFallbackTranslationRejected() throws IOException {
        JsonObject root = root(), ru = ru(), en = en(); en.remove("story.bekhilitt.end.body");
        assertThrows(IllegalArgumentException.class, () -> EpisodeContent.parse(root, ru, en));
    }
    @Test void invalidTranslationValueRejected() throws IOException {
        JsonObject root = root(), ru = ru(), en = en(); ru.addProperty("story.bekhilitt.end.body", "");
        assertThrows(IllegalArgumentException.class, () -> EpisodeContent.parse(root, ru, en));
    }
}
