package dev.bekhilitt.platform;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

/** Recognizes the parsed vanilla rule mutation, never text inside another command. */
public final class PracticeCommandGuard {
    private PracticeCommandGuard() { }

    /** Generic so the exact Brigadier grammar can be tested without a game server. */
    public static boolean changesMobGriefing(ParseResults<?> parsed) {
        // An incomplete/invalid command cannot execute a rule mutation.
        if (parsed.getReader().canRead()) return false;
        for (CommandContextBuilder<?> context = parsed.getContext(); context != null; context = context.getChild()) {
            if (changesMobGriefing(context)) return true;
        }
        return false;
    }

    private static boolean changesMobGriefing(CommandContextBuilder<?> context) {
        var nodes = context.getNodes();
        // /execute ... run redirects to the root, giving a child whose first
        // parsed literal is gamerule. A namespaced alias can instead redirect
        // straight to gamerule, making it the child context's root node.
        int ruleIndex;
        if (gamerule(context.getRootNode())) ruleIndex = 0;
        else if (!nodes.isEmpty() && gamerule(nodes.getFirst().getNode())) ruleIndex = 1;
        else return false;
        if (nodes.size() != ruleIndex + 2 || context.getCommand() == null) return false;
        if (!literal(nodes.get(ruleIndex).getNode(), "mobGriefing")) return false;
        CommandNode<?> value = nodes.get(ruleIndex + 1).getNode();
        // Pinned 1.21.1 GameRuleCommand: literal rule id -> boolean "value".
        return value instanceof ArgumentCommandNode<?, ?> argument
            && "value".equals(argument.getName()) && argument.getType() instanceof BoolArgumentType;
    }

    private static boolean gamerule(CommandNode<?> node) {
        return literal(node, "gamerule") || literal(node, "minecraft:gamerule");
    }

    private static boolean literal(CommandNode<?> node, String expected) {
        return node instanceof LiteralCommandNode<?> && expected.equals(node.getName());
    }
}
