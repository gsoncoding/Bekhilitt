package dev.bekhilitt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client owns only rebindable input; commands contain intentions, never results. */
public final class BekhilittClient implements ClientModInitializer {
    private int state;
    private boolean attackHeld, dodgeHeld;
    @Override public void onInitializeClient() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.bekhilitt.protocol.ArenaStatePayload.TYPE,
            (payload, context) -> state = payload.state());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = 0);
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bekhilitt", "combat"));
        var attack = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bekhilitt.attack", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        var guard = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bekhilitt.guard", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        var dodge = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bekhilitt.dodge", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean attackPressed = attack.isDown() && !attackHeld;
            boolean dodgePressed = dodge.isDown() && !dodgeHeld;
            attackHeld = attack.isDown(); dodgeHeld = dodge.isDown();
            while (attack.consumeClick()) { } while (dodge.consumeClick()) { }
            if (state != 1 || client.player == null || client.screen != null || !client.isWindowActive()) {
                return;
            }
            if (attackPressed) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.bekhilitt.protocol.ControlPayload(0));
            if (guard.isDown()) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.bekhilitt.protocol.ControlPayload(1));
            if (dodgePressed) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.bekhilitt.protocol.ControlPayload(2));
        });
    }
}
