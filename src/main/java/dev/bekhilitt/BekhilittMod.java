package dev.bekhilitt;

import dev.bekhilitt.platform.PrototypeArena;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.commands.Commands;
import net.minecraft.world.InteractionResult;

public final class BekhilittMod implements ModInitializer {
    @Override public void onInitialize() {
        var arena = new PrototypeArena();
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(dev.bekhilitt.protocol.ControlPayload.TYPE, dev.bekhilitt.protocol.ControlPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(dev.bekhilitt.protocol.ArenaStatePayload.TYPE, dev.bekhilitt.protocol.ArenaStatePayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.bekhilitt.protocol.ControlPayload.TYPE, (payload, context) -> {
            if (payload.action() >= 0 && payload.action() < 3)
                arena.command(context.player().createCommandSourceStack(), new String[]{"attack", "guard", "dodge"}[payload.action()]);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var root = Commands.literal("bekhilitt");
            for (String action : new String[]{"start", "stop", "attack", "guard", "dodge"})
                root.then(Commands.literal(action).executes(ctx -> arena.command(ctx.getSource(), action)));
            dispatcher.register(root);
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (arena.isParticipant(handler.player.getUUID())) arena.close(server);
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !arena.isParticipant(entity.getUUID()));
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            arena.isParticipant(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register(arena::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(arena::close);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            arena.isParticipant(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
    }
}
