package dev.bekhilitt.client.ui;

import dev.bekhilitt.client.ClientControls;
import dev.bekhilitt.protocol.EpisodeAdvancePayload;
import dev.bekhilitt.protocol.EpisodeStatePayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A disposable view of the server snapshot; never saves or advances campaign state locally. */
public final class EpisodePresentation {
    private EpisodeStatePayload snapshot;
    private EpisodeScreen screen;
    private EpisodeStatePayload retainedResult;
    private CameraType cameraBeforeLease;
    private CameraType leasedCamera;
    private boolean cameraNeedsConfirmation;
    private boolean cancelPending;
    private boolean nextPending;

    public boolean active() { return snapshot != null && snapshot.active(); }
    public boolean locked() { return active() && snapshot.locked(); }
    public boolean blocksWorldInput() { return locked() || (active() && screen != null); }
    public boolean isResultScreen() { return retainedResult != null; }
    public boolean pending() { return nextPending || cancelPending; }

    public void receive(Minecraft client, EpisodeStatePayload incoming) {
        if (snapshot != null && incoming.revision() <= snapshot.revision()) return;
        long previousRevision = snapshot == null ? -1 : snapshot.revision();
        snapshot = incoming;
        nextPending = false;
        if (incoming.active()) {
            retainedResult = "end".equals(incoming.phase()) && !cancelPending ? incoming : null;
        }
        // If a scene advanced while Escape was in flight, cancel the new revision as well.
        if (cancelPending && incoming.active() && incoming.revision() != previousRevision
                && canSend())
            PacketDistributor.sendToServer(new EpisodeAdvancePayload(incoming.revision(), EpisodeAdvancePayload.Action.CANCEL));
        if (!incoming.active()) { cancelPending = false; retainedResult = null; }
        if ((!incoming.showsScreen() && retainedResult == null) || cancelPending) closeOwnedScreen(client);
        else if (screen != null && incoming.active()) screen.update(incoming);
        syncScreen(client);
        updateCamera(client, false);
    }

    public void tick(Minecraft client) {
        syncScreen(client);
        // Confirm one tick after a camera transition, after queued Epic Fight mode packets.
        // This is not a permanent F5 lock: subsequent manual camera changes remain possible.
        updateCamera(client, true);
    }

    private void syncScreen(Minecraft client) {
        if (client.player == null || client.level == null) return;
        EpisodeStatePayload display = retainedResult != null ? retainedResult : snapshot;
        if (display != null && (display.showsScreen() || retainedResult != null)
                && !cancelPending && !nextPending && screen == null && client.screen == null) {
            if (active()) ClientControls.stopVanillaActions(client);
            screen = new EpisodeScreen(this, display);
            try {
                client.setScreen(screen);
            } catch (RuntimeException | Error failure) {
                screen = null;
                updateCamera(client, false);
                throw failure;
            }
        }
    }

    public void next() {
        if (!active() || (!snapshot.canAdvance() && !isResultScreen()) || pending()
                || !canSend()) return;
        nextPending = true;
        PacketDistributor.sendToServer(new EpisodeAdvancePayload(snapshot.revision(), EpisodeAdvancePayload.Action.NEXT));
    }

    public void cancel(Minecraft client) {
        if (active() && ("end".equals(snapshot.phase()) || "camp_talk".equals(snapshot.phase()))) {
            if (!pending() && canSend()) {
                nextPending = true;
                PacketDistributor.sendToServer(new EpisodeAdvancePayload(snapshot.revision(), EpisodeAdvancePayload.Action.NEXT));
            }
            retainedResult = null;
            closeOwnedScreen(client);
            return;
        }
        retainedResult = null;
        if (active() && !cancelPending) {
            cancelPending = true;
            if (canSend())
                PacketDistributor.sendToServer(new EpisodeAdvancePayload(snapshot.revision(), EpisodeAdvancePayload.Action.CANCEL));
        }
        closeOwnedScreen(client);
    }

    /** Covers screen replacement by the game or another mod as well as explicit Escape. */
    void removed(EpisodeScreen removed) {
        if (screen != removed) return;
        screen = null;
        cancel(Minecraft.getInstance());
    }

    private void closeOwnedScreen(Minecraft client) {
        EpisodeScreen previous = screen;
        screen = null; // removed() must not cancel a server-driven transition.
        try {
            if (previous != null && client.screen == previous) {
                ClientControls.clearVanillaClicks(client);
                client.setScreen(null);
            }
        } finally {
            // Also reached after removed() clears the screen reference, and on disconnect.
            updateCamera(client, false);
        }
    }

    /** Camera intent comes only from the revision-checked episode, never an unversioned HUD packet. */
    private void updateCamera(Minecraft client, boolean confirmTransition) {
        boolean freeCombat = active() && !cancelPending && !snapshot.showsScreen()
            && ("duel".equals(snapshot.phase()) || "practice".equals(snapshot.phase()));
        CameraType requested = active() && screen != null ? CameraType.FIRST_PERSON
            : freeCombat ? CameraType.THIRD_PERSON_BACK : null;
        if (requested == null) {
            if (cameraBeforeLease != null) client.options.setCameraType(cameraBeforeLease);
            // Cancellation can trigger Epic Fight's own mode/camera restore afterwards.
            // Keep the original until the server confirms inactivity, then restore it once more.
            if (!active() || !cancelPending) cameraBeforeLease = null;
            leasedCamera = null;
            cameraNeedsConfirmation = false;
        } else if (requested != leasedCamera) {
            if (cameraBeforeLease == null) cameraBeforeLease = client.options.getCameraType();
            leasedCamera = requested;
            client.options.setCameraType(requested);
            cameraNeedsConfirmation = true;
        } else if (confirmTransition && cameraNeedsConfirmation) {
            client.options.setCameraType(requested);
            cameraNeedsConfirmation = false;
        }
        // Never save options. A retained END card releases the lease as soon as the server returns the player.
    }

    private static boolean canSend() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(EpisodeAdvancePayload.TYPE);
    }

    public void reset(Minecraft client) {
        snapshot = null;
        retainedResult = null;
        nextPending = cancelPending = false;
        closeOwnedScreen(client);
    }

    public void renderObjective(GuiGraphics graphics) { renderObjective(graphics, false); }

    void renderObjective(GuiGraphics graphics, boolean inEpisodeScreen) {
        Minecraft client = Minecraft.getInstance();
        if (!active() || client.player == null || (!inEpisodeScreen && client.screen != null)
                || snapshot.objective().isBlank()) return;
        if ("camp".equals(snapshot.phase()) && !inEpisodeScreen) {
            int panelWidth = Math.min(390, graphics.guiWidth() - 16);
            var campLines = client.font.split(Component.translatable(snapshot.objective()), panelWidth - 16);
            graphics.fill(8, 8, 8 + panelWidth, 20 + campLines.size() * 11, 0xB510151C);
            for (int i = 0; i < campLines.size(); i++)
                graphics.drawString(client.font, campLines.get(i), 16, 14 + i * 11, 0xFFE9DEC7, false);
            return;
        }
        if ("practice".equals(snapshot.phase()) && !inEpisodeScreen) {
            renderPracticeObjective(graphics, client);
            return;
        }
        int maxWidth = Math.max(40, Math.min(340, graphics.guiWidth() / 2 - 16));
        var lines = client.font.split(Component.translatable(snapshot.objective()), maxWidth - 16);
        int lineCount = Math.min(lines.size(), Math.max(1, (graphics.guiHeight() / 3 - 28) / 11));
        var adaptation = client.font.split(Component.translatable("ui.bekhilitt.adaptation"), maxWidth - 16);
        int footerHeight = inEpisodeScreen ? 0 : 3 + adaptation.size() * 10;
        graphics.fill(8, 8, maxWidth + 8, 29 + lineCount * 11 + footerHeight, 0xE0151820);
        graphics.drawString(client.font, Component.translatable("ui.bekhilitt.objective"), 16, 14, 0xFFE5C886, false);
        for (int i = 0; i < lineCount; i++)
            graphics.drawString(client.font, lines.get(i), 16, 27 + i * 11, 0xFFF2EEE6, false);
        if (!inEpisodeScreen)
            for (int i = 0; i < adaptation.size(); i++)
                graphics.drawString(client.font, adaptation.get(i), 16, 30 + lineCount * 11 + i * 10, 0xFFB5B7BE, false);
    }

    /** Instructional overlay only; all Leap/Slam input and execution stay inside Epic Fight. */
    private void renderPracticeObjective(GuiGraphics graphics, Minecraft client) {
        // Keep the aiming reticle and aerial landing target visible beside the instructions.
        int panelWidth = Math.max(100, Math.min(340, graphics.guiWidth() / 2 - 16));
        int textWidth = panelWidth - 16;
        var titleLines = client.font.split(Component.translatable("ui.bekhilitt.practice"), textWidth);
        var objectiveLines = client.font.split(Component.translatable(snapshot.objective()), textWidth);
        var leapLines = client.font.split(Component.translatable("hud.bekhilitt.practice.leap",
            Component.keybind("key.epicfight.mover_skill")), textWidth);
        var slamLines = client.font.split(Component.translatable("hud.bekhilitt.practice.slam",
            Component.keybind("key.epicfight.attack")), textWidth);
        var exitLines = client.font.split(Component.translatable("hud.bekhilitt.practice.exit"), textWidth);
        int rows = titleLines.size() + objectiveLines.size() + leapLines.size() + slamLines.size() + exitLines.size();
        graphics.fill(8, 8, panelWidth + 8, 22 + rows * 11, 0xE0151820);
        int y = 14;
        for (var line : titleLines) {
            graphics.drawString(client.font, line, 16, y, 0xFFE5C886, false);
            y += 11;
        }
        for (var group : java.util.List.of(objectiveLines, leapLines, slamLines, exitLines)) {
            for (var line : group) {
                graphics.drawString(client.font, line, 16, y, 0xFFF2EEE6, false);
                y += 11;
            }
        }
    }

}
