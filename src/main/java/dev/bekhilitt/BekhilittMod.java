package dev.bekhilitt;

import dev.bekhilitt.platform.FirstEpisode;
import dev.bekhilitt.platform.PracticeCommandGuard;
import net.neoforged.neoforge.event.CommandEvent;
import net.minecraft.network.chat.Component;
import dev.bekhilitt.platform.EpicFightTrainingLoadout;
import dev.bekhilitt.platform.story.EpisodeCamp;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import dev.bekhilitt.platform.networking.Network;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod("bekhilitt")
public final class BekhilittMod {
    private final FirstEpisode episode = new FirstEpisode();
    public BekhilittMod(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> Network.register(event,episode));
        episode.registerEpicHooks();
        var bus = NeoForge.EVENT_BUS;
        dev.bekhilitt.platform.CampSafetyEvents.register(bus, episode);
        bus.addListener((CommandEvent event) -> {
            var parse = event.getParseResults();
            var source = parse.getContext().getSource();
            if (episode.blocksPracticeRuleChange(source.getServer())
                    && PracticeCommandGuard.changesMobGriefing(parse)) {
                event.setCanceled(true);
                source.sendFailure(Component.literal("Bekhilitt: сначала /bekhilitt stop, затем меняйте mobGriefing. Незавершённое восстановление навыков сохраняет защиту."));
            }
        });
        bus.addListener((RegisterCommandsEvent event) -> {
            var root=Commands.literal("bekhilitt");
            root.then(Commands.literal("start").executes(ctx -> episode.command(ctx.getSource(),"start"))
                .then(Commands.literal("disposable").executes(ctx -> episode.command(ctx.getSource(),"start_disposable"))));
            for (String action:new String[]{"resume","stop","status","talk","practice","camp"})
                root.then(Commands.literal(action).executes(ctx -> episode.command(ctx.getSource(),action)));
            event.getDispatcher().register(root);
        });
        bus.addListener((EntityJoinLevelEvent event) -> {
            // Scene actors are reconstructed from the server campaign checkpoint.
            // A disk-loaded actor after a crash must never resume autonomous AI.
            if (!event.getLevel().isClientSide() && event.loadedFromDisk()
                    && event.getEntity().getTags().contains(EpisodeCamp.TAG)) {
                event.setCanceled(true);
            }
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if(event.getEntity() instanceof ServerPlayer p) episode.joined(p.server,p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if(event.getEntity() instanceof ServerPlayer p) episode.disconnected(p.server,p);
        });
        bus.addListener((PlayerEvent.PlayerRespawnEvent event) -> {
            if(event.getEntity() instanceof ServerPlayer p) episode.respawned(p.server,p);
        });
        bus.addListener((EntityInvulnerabilityCheckEvent event) -> {
            if (!event.getEntity().level().isClientSide()
                    && episode.protectPracticeEntity(event.getEntity(), event.getSource())) {
                event.setInvulnerable(true);
            }
        });
        bus.addListener((LivingIncomingDamageEvent event) -> {
            if(episode.cancelDamage(event.getEntity(),event.getSource())) event.setCanceled(true);
        });
        bus.addListener((LivingDamageEvent.Pre event) -> event.setNewDamage(episode.limitDamage(event.getEntity(),event.getSource(),event.getNewDamage())));
        bus.addListener((LivingDamageEvent.Post event) -> episode.damaged(event.getEntity(),event.getSource(),event.getNewDamage()));
        bus.addListener((LivingDeathEvent event) -> { if(episode.preventDeath(event.getEntity())) event.setCanceled(true); });
        bus.addListener((PlayerInteractEvent.EntityInteract event) -> {
            if(event.getEntity() instanceof ServerPlayer p) episode.interactCamp(p,event.getTarget());
            if(episode.blocksInteraction(event.getEntity().getUUID())) event.setCanceled(true);
        });
        bus.addListener((PlayerInteractEvent.EntityInteractSpecific event) -> {
            if(event.getEntity() instanceof ServerPlayer p) episode.interactCamp(p,event.getTarget());
            if(episode.blocksInteraction(event.getEntity().getUUID())) event.setCanceled(true);
        });
        bus.addListener((PlayerInteractEvent.RightClickBlock event) -> { if(episode.blocksInteraction(event.getEntity().getUUID())) event.setCanceled(true); });
        bus.addListener((AttackEntityEvent event) -> { if(episode.blocksAttack(event.getEntity().getUUID(),event.getTarget())) event.setCanceled(true); });
        bus.addListener((ItemTossEvent event) -> {
            // Canceling a toss alone deletes its stack. First return the temporary
            // one-item sword to inventory, and cancel only after that succeeds.
            if (episode.isParticipant(event.getPlayer().getUUID())
                    && (EpicFightTrainingLoadout.temporary(event.getEntity().getItem())
                        || (event.getPlayer() instanceof ServerPlayer cp && dev.bekhilitt.platform.CampPlayerLease.exists(cp)))) {
                var returned = event.getEntity().getItem().copy();
                var inventory = event.getPlayer().getInventory();
                boolean restored;
                if (inventory.getItem(inventory.selected).isEmpty()) {
                    inventory.setItem(inventory.selected, returned);
                    restored = true;
                } else {
                    restored = inventory.add(returned);
                }
                if (restored) {
                    event.setCanceled(true);
                    inventory.setChanged();
                    event.getPlayer().inventoryMenu.broadcastChanges();
                }
            }
        });
        bus.addListener((ServerTickEvent.Pre event) -> episode.preTick(event.getServer()));
        bus.addListener((ServerTickEvent.Post event) -> episode.tick(event.getServer()));
        bus.addListener((ServerStoppingEvent event) -> episode.stopping(event.getServer()));
    }
}
