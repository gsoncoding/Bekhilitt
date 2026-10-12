package dev.bekhilitt;

import com.mojang.blaze3d.platform.InputConstants;
import dev.bekhilitt.client.CombatContext;
import dev.bekhilitt.protocol.ArenaStatePayload;
import dev.bekhilitt.protocol.ControlPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Named, rebindable mod actions. Vanilla bindings and mouse camera are never rewritten. */
public final class BekhilittClient implements ClientModInitializer {
    private int state;
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ArenaStatePayload.TYPE, (payload, context) -> {
            var client = context.client();
            state = payload.state();
            CombatContext.INPUT.session(state != 0, client.options.keyAttack.isDown(), client.options.keyUse.isDown());
            if (state != 0 && client.gameMode != null && client.player != null) {
                client.gameMode.stopDestroyBlock();
                if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            state = 0; CombatContext.INPUT.reset();
        });
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bekhilitt", "combat"));
        var attack = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bekhilitt.attack", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, category));
        var guard = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bekhilitt.guard", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, category));
        var dodge = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bekhilitt.dodge", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        // Migrate only the old prototype's exact mod defaults after Minecraft loads options.
        // Custom mod bindings and every vanilla binding remain untouched. No options file is rewritten here.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            migrateDefault(attack, GLFW.GLFW_KEY_J, InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT));
            migrateDefault(guard, GLFW.GLFW_KEY_K, InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
            migrateDefault(dodge, GLFW.GLFW_KEY_L, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R));
            KeyMapping.resetMapping();
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            boolean attackClick = drain(attack), dodgeClick = drain(dodge);
            drain(guard);
            boolean eligible = state == 1 && client.player != null && client.screen == null
                && client.isWindowActive() && client.mouseHandler.isMouseGrabbed();
            var frame = CombatContext.INPUT.sample(eligible, attack.isDown(), attackClick,
                guard.isDown(), dodge.isDown(), dodgeClick, client.options.keyAttack.isDown(), client.options.keyUse.isDown());
            if (client.player == null || !ClientPlayNetworking.canSend(ControlPayload.TYPE)) return;
            if (frame.releaseGuard()) ClientPlayNetworking.send(new ControlPayload(3));
            if (frame.attack()) ClientPlayNetworking.send(new ControlPayload(0));
            if (frame.guard()) ClientPlayNetworking.send(new ControlPayload(1));
            if (frame.dodge()) ClientPlayNetworking.send(new ControlPayload(2));
        });
    }
    private static boolean drain(KeyMapping binding) {
        boolean clicked = false;
        while (binding.consumeClick()) clicked = true;
        return clicked;
    }
    private static void migrateDefault(KeyMapping binding, int oldCode, InputConstants.Key replacement) {
        if (KeyBindingHelper.getBoundKeyOf(binding).equals(InputConstants.Type.KEYSYM.getOrCreate(oldCode)))
            binding.setKey(replacement);
    }
}
