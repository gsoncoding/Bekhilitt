package dev.bekhilitt.platform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;
import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.*;

class PracticeCommandGuardTest {
    private final Object source = new Object();
    private final CommandDispatcher<Object> dispatcher = dispatcher();

    // Mirrors GameRuleCommand.register in pinned Minecraft 1.21.1, without
    // bootstrapping registries or granting/altering any real game rule.
    private static LiteralArgumentBuilder<Object> rule(String name) {
        return LiteralArgumentBuilder.<Object>literal(name)
            .then(LiteralArgumentBuilder.<Object>literal("mobGriefing").executes(c -> 1)
                .then(argument("value", bool()).executes(c -> 1)))
            .then(LiteralArgumentBuilder.<Object>literal("doMobSpawning").executes(c -> 1)
                .then(argument("value", bool()).executes(c -> 1)));
    }

    private static CommandDispatcher<Object> dispatcher() {
        var dispatcher = new CommandDispatcher<Object>();
        var gamerule = dispatcher.register(rule("gamerule"));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("minecraft:gamerule").redirect(gamerule));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("say")
            .then(argument("message", greedyString()).executes(c -> 1)));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("other").then(rule("gamerule")));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("execute")
            .then(LiteralArgumentBuilder.<Object>literal("run").redirect(dispatcher.getRoot()))
            .then(LiteralArgumentBuilder.<Object>literal("as")
                .then(argument("targets", word()).then(LiteralArgumentBuilder.<Object>literal("run")
                    .fork(dispatcher.getRoot(), c -> java.util.List.of(c.getSource()))))));
        return dispatcher;
    }

    private boolean changes(String command) {
        return PracticeCommandGuard.changesMobGriefing(dispatcher.parse(command, source));
    }

    @Test void directBooleanWritesAreGuardedRegardlessOfValue() {
        assertTrue(changes("gamerule mobGriefing true"));
        assertTrue(changes("gamerule mobGriefing false"));
    }

    @Test void readOnlyQueriesRemainAvailable() {
        assertFalse(changes("gamerule mobGriefing"));
        assertFalse(changes("minecraft:gamerule mobGriefing"));
        assertFalse(changes("execute run gamerule mobGriefing"));
    }

    @Test void unrelatedRulesRemainAvailable() {
        assertFalse(changes("gamerule doMobSpawning true"));
        assertFalse(changes("execute run gamerule doMobSpawning false"));
    }

    @Test void commandTextInsideSayIsNotAMutation() {
        assertFalse(changes("say gamerule mobGriefing true"));
        assertFalse(changes("say /minecraft:gamerule mobGriefing false"));
        assertFalse(changes("execute run say gamerule mobGriefing true"));
    }

    @Test void executeRedirectsAndNestedRedirectsAreGuarded() {
        assertTrue(changes("execute run gamerule mobGriefing true"));
        assertTrue(changes("execute run execute run gamerule mobGriefing false"));
        assertTrue(changes("execute as self run gamerule mobGriefing true"));
    }

    @Test void namespacedAliasRedirectIsGuarded() {
        assertTrue(changes("minecraft:gamerule mobGriefing true"));
        assertTrue(changes("execute run minecraft:gamerule mobGriefing false"));
    }

    @Test void namespacedLiteralCopyIsGuarded() {
        var direct = new CommandDispatcher<Object>();
        direct.register(rule("minecraft:gamerule"));
        assertTrue(PracticeCommandGuard.changesMobGriefing(direct.parse("minecraft:gamerule mobGriefing true", source)));
    }

    @Test void unrelatedNestedLiteralDoesNotCountAsTheRootCommand() {
        assertFalse(changes("other gamerule mobGriefing true"));
    }

    @Test void incompleteAndInvalidCommandsAreNotMutations() {
        assertFalse(changes("gamerule"));
        assertFalse(changes("gamerule mobGriefing invalid"));
        assertFalse(changes("gamerule mobGriefing true extra"));
        assertFalse(changes("execute run gamerule mobGriefing"));
    }

    @Test void parsingAfterLeadingSlashUsesParsedNodesRatherThanOffsets() {
        var reader = new StringReader("/gamerule mobGriefing true");
        reader.skip();
        assertTrue(PracticeCommandGuard.changesMobGriefing(dispatcher.parse(reader, source)));
    }
}
