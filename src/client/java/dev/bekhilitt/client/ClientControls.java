package dev.bekhilitt.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Scene-only cleanup. This class is never used to capture or replace Epic Fight combat input. */
public final class ClientControls {
    private ClientControls() { }

    private static void drain(KeyMapping binding) {
        while (binding.consumeClick()) { }
    }

    public static void clearVanillaClicks(Minecraft client) {
        drain(client.options.keyAttack);
        drain(client.options.keyUse);
    }

    public static void stopVanillaActions(Minecraft client) {
        clearVanillaClicks(client);
        if (client.gameMode != null && client.player != null) {
            client.gameMode.stopDestroyBlock();
            if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
        }
    }
}
