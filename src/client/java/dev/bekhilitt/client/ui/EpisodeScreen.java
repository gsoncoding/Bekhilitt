package dev.bekhilitt.client.ui;

import dev.bekhilitt.protocol.EpisodeStatePayload;
import dev.bekhilitt.presentation.DialogueLayout;
import dev.bekhilitt.presentation.DialoguePager;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Bottom-anchored subtitle panel. Reading never sends an advance until the final local page. */
public final class EpisodeScreen extends Screen {
    private final EpisodePresentation owner;
    private EpisodeStatePayload snapshot;
    private final DialoguePager pager = new DialoguePager();
    private List<FormattedCharSequence> lines = List.of();
    private DialogueLayout layout;
    private Button next, previous;

    EpisodeScreen(EpisodePresentation owner, EpisodeStatePayload snapshot) {
        super(Component.translatable("ui.bekhilitt.episode"));
        this.owner = owner; this.snapshot = snapshot;
    }
    void update(EpisodeStatePayload incoming) {
        if (!snapshot.body().equals(incoming.body()) || snapshot.page() != incoming.page()
                || !snapshot.phase().equals(incoming.phase())) pager.reset();
        snapshot = incoming;
        rebuildWidgets();
    }
    @Override protected void init() {
        layout = DialogueLayout.fit(width, height, 1);
        lines = font.split(Component.translatable(snapshot.body()), layout.textWidth());
        layout = DialogueLayout.fit(width, height, lines.size());
        pager.layout(lines.size(), layout.linesPerPage());
        int buttonWidth = Math.min(132, (layout.width() - 70) / 2);
        previous = addRenderableWidget(Button.builder(Component.translatable("ui.bekhilitt.previous"), b -> previous())
            .bounds(layout.x() + 10, layout.buttonsY(), buttonWidth, 20).build());
        next = addRenderableWidget(Button.builder(Component.translatable("ui.bekhilitt.continue"), b -> next())
            .bounds(layout.x() + layout.width() - 10 - buttonWidth, layout.buttonsY(), buttonWidth, 20).build());
        setInitialFocus(next);
        refresh();
    }
    private void next() {
        if (!pager.take(owner.pending())) return;
        if (pager.more()) pager.next();
        else if (snapshot.canAdvance() || owner.isResultScreen()) owner.next();
        refresh();
    }
    private void previous() {
        if (pager.page() == 0 || !pager.take(owner.pending())) return;
        pager.previous(); refresh();
    }
    private void refresh() {
        if (next == null) return;
        previous.visible = pager.pages() > 1;
        previous.active = pager.page() > 0 && pager.ready() && !owner.pending();
        next.visible = snapshot.canAdvance() || pager.more() || owner.isResultScreen();
        next.active = pager.ready() && !owner.pending();
        next.setMessage(Component.translatable(pager.more() ? "ui.bekhilitt.read_more"
            : owner.isResultScreen() ? "ui.bekhilitt.stay_camp"
            : "camp_talk".equals(snapshot.phase()) ? "gui.done" : "ui.bekhilitt.continue"));
    }
    @Override public void tick() {
        long w = minecraft.getWindow().getWindow();
        boolean released = GLFW.glfwGetMouseButton(w, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE;
        for (int key : new int[]{GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE,
                GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT}) released &= GLFW.glfwGetKey(w, key) == GLFW.GLFW_RELEASE;
        if (released) pager.release();
        refresh();
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_LEFT) { previous(); return true; }
        if (key == GLFW.GLFW_KEY_RIGHT) { next(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            if (getFocused() == previous && previous.active) previous(); else next();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(layout.x(), layout.y(), layout.x()+layout.width(), layout.y()+layout.height(), 0xCE10151C);
        g.fill(layout.x(), layout.y(), layout.x()+layout.width(), layout.y()+1, 0xFFD5B16B);
        String speaker = Component.translatable(snapshot.speaker()).getString();
        g.drawString(font, font.plainSubstrByWidth(speaker, layout.textWidth() - 65), layout.x()+10, layout.y()+8, 0xFFE5C886, false);
        String hint = owner.pending() ? "…" : "Esc";
        g.drawString(font, hint, layout.x()+layout.width()-10-font.width(hint), layout.y()+8, 0xFFB5B7BE, false);
        for (int i=pager.from(); i<pager.to(); i++)
            g.drawString(font, lines.get(i), layout.x()+10, layout.bodyY()+(i-pager.from())*DialogueLayout.LINE_HEIGHT, 0xFFF2EEE6, false);
        if (pager.pages()>1) {
            String count=(pager.page()+1)+" / "+pager.pages();
            g.drawCenteredString(font,count,layout.x()+layout.width()/2,layout.buttonsY()+6,0xFFE5C886);
        }
        if (!next.visible) g.drawString(font, Component.translatable("ui.bekhilitt.scene"),layout.x()+10,layout.buttonsY()+6,0xFFB5B7BE,false);
        super.render(g,mouseX,mouseY,partialTick);
    }
    @Override public void renderBackground(GuiGraphics g, int x, int y, float tick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { owner.cancel(minecraft); }
    @Override public void removed() { owner.removed(this); }
}
