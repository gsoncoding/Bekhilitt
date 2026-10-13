package dev.bekhilitt.client;

import dev.bekhilitt.client.ui.CombatHud;
import dev.bekhilitt.client.ui.EpisodePresentation;
import dev.bekhilitt.platform.networking.Network;
import dev.bekhilitt.protocol.ArenaStatePayload;
import dev.bekhilitt.protocol.CombatStatusPayload;
import dev.bekhilitt.protocol.EpisodeStatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only presentation. Epic Fight owns every playable combat input and animation. */
@Mod(value = "bekhilitt", dist = Dist.CLIENT)
public final class BekhilittNeoClient {
    private static final EpisodePresentation EPISODE = new EpisodePresentation();
    private static final CombatHud COMBAT_HUD = new CombatHud();

    public BekhilittNeoClient(net.neoforged.bus.api.IEventBus modEventBus) {
        dev.bekhilitt.client.render.CascaBodyRenderer.register(modEventBus, EPISODE::active);
        dev.bekhilitt.client.render.GriffithBodyRenderer.register(EPISODE::active);
        dev.bekhilitt.client.render.GutsBodyRenderer.register(EPISODE::active);
        // Common network registration never loads a Minecraft client class on a dedicated server.
        Network.clientArena = BekhilittNeoClient::receiveArena;
        Network.clientCombat = BekhilittNeoClient::receiveCombat;
        Network.clientEpisode = BekhilittNeoClient::receiveEpisode;
        NeoForge.EVENT_BUS.addListener(BekhilittNeoClient::tick);
        NeoForge.EVENT_BUS.addListener(BekhilittNeoClient::render);
        NeoForge.EVENT_BUS.addListener(BekhilittNeoClient::logout);
        NeoForge.EVENT_BUS.addListener(BekhilittNeoClient::sceneInteraction);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BekhilittNeoClient::sceneMovement);
    }

    /** Invoked by Network's clientbound handler on the game thread. */
    public static void receiveArena(ArenaStatePayload payload) {
        COMBAT_HUD.session(payload.state());
    }

    public static void receiveCombat(CombatStatusPayload payload) { COMBAT_HUD.receive(payload); }

    public static void receiveEpisode(EpisodeStatePayload payload) {
        Minecraft client = Minecraft.getInstance();
        EPISODE.receive(client, payload);
        if (EPISODE.blocksWorldInput()) ClientControls.stopVanillaActions(client);
    }

    private static void tick(ClientTickEvent.Pre event) {
        Minecraft client = Minecraft.getInstance();
        EPISODE.tick(client);
        COMBAT_HUD.tick();
        // Never drain clicks, rewrite bindings, or send custom combat packets in a duel.
        if (EPISODE.blocksWorldInput()) ClientControls.stopVanillaActions(client);
    }

    private static void render(RenderGuiEvent.Post event) {
        EPISODE.renderObjective(event.getGuiGraphics());
        COMBAT_HUD.render(event.getGuiGraphics());
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        COMBAT_HUD.session(0);
        EPISODE.reset(Minecraft.getInstance());
    }

    private static void sceneInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!EPISODE.blocksWorldInput()) return;
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    private static void sceneMovement(MovementInputUpdateEvent event) {
        if (!EPISODE.blocksWorldInput()) return;
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = input.shiftKeyDown = false;
    }
}
