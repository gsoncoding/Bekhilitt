package dev.bekhilitt.platform.networking;

import java.util.function.Consumer;
import dev.bekhilitt.platform.FirstEpisode;
import dev.bekhilitt.protocol.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Only revision-checked story intents cross C2S; Epic Fight owns its combat transport. */
public final class Network {
    public static Consumer<ArenaStatePayload> clientArena = value -> {};
    public static Consumer<CombatStatusPayload> clientCombat = value -> {};
    public static Consumer<EpisodeStatePayload> clientEpisode = value -> {};
    private Network() {}
    public static void register(RegisterPayloadHandlersEvent event, FirstEpisode episode) {
        var r = event.registrar("2");
        r.playToServer(EpisodeAdvancePayload.TYPE, EpisodeAdvancePayload.CODEC, (p,c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) episode.advance(player,p);
        }));
        r.playToClient(ArenaStatePayload.TYPE, ArenaStatePayload.CODEC, (p,c) -> c.enqueueWork(() -> clientArena.accept(p)));
        r.playToClient(CombatStatusPayload.TYPE, CombatStatusPayload.CODEC, (p,c) -> c.enqueueWork(() -> clientCombat.accept(p)));
        r.playToClient(EpisodeStatePayload.TYPE, EpisodeStatePayload.CODEC, (p,c) -> c.enqueueWork(() -> clientEpisode.accept(p)));
    }
    public static void send(ServerPlayer player, CustomPacketPayload payload) { PacketDistributor.sendToPlayer(player,payload); }
}
