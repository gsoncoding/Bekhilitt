package dev.bekhilitt.client.ui;

import dev.bekhilitt.protocol.CombatStatusPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Server-owned results and Epic Fight's actual bindings; never owns combat rules or input. */
public final class CombatHud {
    private static final CombatStatusPayload EMPTY = new CombatStatusPayload(0, 0, 0, 0, false, 0);
    private CombatStatusPayload snapshot = EMPTY;
    private int arenaState, feedback, feedbackTicks;

    public void session(int state) {
        if (state == 0 || arenaState == 0) {
            snapshot = EMPTY;
            feedback = feedbackTicks = 0;
        }
        arenaState = state;
    }

    public void receive(CombatStatusPayload incoming) {
        if (arenaState == 0) return;
        snapshot = incoming;
        if (incoming.feedback() != 0) {
            feedback = incoming.feedback();
            feedbackTicks = 35;
        }
    }

    public void tick() {
        if (feedbackTicks > 0 && --feedbackTicks == 0) feedback = 0;
    }

    public void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if ((arenaState != 1 && arenaState != 2) || client.player == null || client.screen != null) return;
        int availableWidth = Math.max(40, Math.min(420, graphics.guiWidth() - 32));
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.addAll(client.font.split(status(client), availableWidth - 20));
        lines.addAll(client.font.split(Component.translatable("hud.bekhilitt.counters",
            Math.min(2, snapshot.landed()), Math.min(1, snapshot.blocks()), Math.min(1, snapshot.dodges())), availableWidth - 20));
        if (arenaState == 1) {
            String training = snapshot.landed() < 2 ? "hit" : snapshot.blocks() < 1 ? "guard"
                : snapshot.dodges() < 1 ? "dodge" : "ready";
            lines.addAll(client.font.split(Component.translatable("hud.bekhilitt.training." + training), availableWidth - 20));
        }
        KeyMapping mode = findBinding(client, "key.epicfight.switch_mode");
        if (arenaState == 1 && mode != null)
            lines.addAll(client.font.split(Component.literal("Epic Fight · ")
                .append(Component.translatable(mode.getName())).append(": ")
                .append(mode.getTranslatedKeyMessage()), availableWidth - 20));
        int panelWidth = Math.min(availableWidth, lines.stream().mapToInt(client.font::width).max().orElse(0) + 20);
        int centerX = graphics.guiWidth() / 2;
        int panelHeight = lines.size() * 12 + 10;
        int top = Math.max(8, graphics.guiHeight() - 76 - panelHeight);
        int color = arenaState == 2 ? 0xFFE5C886 : snapshot.windup() ? 0xFFFFBA68
            : feedback == 2 ? 0xFFFF9B98 : 0xFFF2EEE6;
        graphics.fill(centerX - panelWidth / 2, top, centerX + (panelWidth + 1) / 2, top + panelHeight, 0xF0151820);
        for (int i = 0; i < lines.size(); i++)
            graphics.drawString(client.font, lines.get(i), centerX - client.font.width(lines.get(i)) / 2,
                top + 5 + i * 12, i == 0 ? color : 0xFFE5C886, false);
    }

    private Component status(Minecraft client) {
        if (arenaState == 2) return Component.translatable("hud.bekhilitt.staging");
        if (snapshot.windup()) return Component.translatable("hud.bekhilitt.windup");
        if (feedback != 0) return Component.translatable("hud.bekhilitt.feedback." + feedback);
        if (snapshot.stance() != 0) return Component.translatable("hud.bekhilitt.stance." + snapshot.stance());
        KeyMapping attack = findBinding(client, "key.epicfight.attack");
        KeyMapping guard = findBinding(client, "key.epicfight.guard");
        KeyMapping dodge = findBinding(client, "key.epicfight.dodge");
        if (attack == null || guard == null || dodge == null) return Component.translatable("hud.bekhilitt.ready");
        return Component.translatable("hud.bekhilitt.controls", attack.getTranslatedKeyMessage(),
            guard.getTranslatedKeyMessage(), dodge.getTranslatedKeyMessage());
    }

    /** Read registered mappings without any hard-coded key codes, mutation, or click consumption. */
    private static KeyMapping findBinding(Minecraft client, String name) {
        for (KeyMapping binding : client.options.keyMappings) if (name.equals(binding.getName())) return binding;
        return null;
    }
}
