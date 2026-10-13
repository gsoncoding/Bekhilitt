package dev.bekhilitt.platform;

import dev.bekhilitt.platform.story.CampSafetyRules;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** NeoForge adapters for the active free camp only; the combat duel keeps its own boundary. */
public final class CampSafetyEvents {
    private CampSafetyEvents() { }

    public static void register(IEventBus bus, FirstEpisode episode) {
        bus.addListener((ItemEntityPickupEvent.Pre event) -> denyPickup(event,
            event.getPlayer() instanceof ServerPlayer player && CampPlayerLease.exists(player)));
        bus.addListener((PlayerXpEvent.PickupXp event) -> denyXpPickup(event,
            event.getEntity() instanceof ServerPlayer player && CampPlayerLease.exists(player)));
        bus.addListener((ExplosionEvent.Detonate event) -> {
            CampSafetyRules.Region region = episode.activeCampSafety(event.getLevel());
            if (region == null) return;
            // Filter affected positions, not the explosion centre: blasts just outside the border count too.
            event.getAffectedBlocks().removeIf(pos -> region.contains(pos.getX(), pos.getY(), pos.getZ()));
            // Native knockback is independent of hurt()/invulnerability.
            event.getAffectedEntities().removeIf(episode::protectsCampActor);
        });
        bus.addListener((EntityMobGriefingEvent event) -> {
            var entity = event.getEntity();
            CampSafetyRules.Region region = episode.activeCampSafety(entity.level());
            if (region != null && region.withinMobReach(entity.getX(), entity.getY(), entity.getZ()))
                event.setCanGrief(false);
        });
        bus.addListener((LivingChangeTargetEvent event) -> denyTarget(event,
            episode.protectsCampActor(event.getNewAboutToBeSetTarget())));
        bus.addListener((EntityTickEvent.Pre event) -> {
            if (!(event.getEntity() instanceof Mob mob)) return;
            CampSafetyRules.Region region = episode.activeCampSafety(mob.level());
            if (region == null) return;
            boolean targetProtected = episode.protectsCampActor(mob.getTarget());
            boolean brainTargetProtected = mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).filter(episode::protectsCampActor).isPresent();
            if (targetProtected || brainTargetProtected) {
                if (targetProtected) mob.setTarget(null);
                if (brainTargetProtected) {
                    mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                    mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
                mob.getNavigation().stop();
                mob.setAggressive(false);
            }
            // SwellGoal caches its target separately and follows it up to seven blocks away.
            // An already targeted visitor must be released even outside the block-grief margin.
            if (mob instanceof Creeper creeper && CampSafetyRules.defuseCreeper(creeper.isIgnited(),
                    creeper.getTarget() != null, targetProtected || brainTargetProtected,
                    region.withinMobReach(creeper.getX(), creeper.getY(), creeper.getZ()))) {
                for (var goal : creeper.goalSelector.getAvailableGoals())
                    if (goal.isRunning() && goal.getGoal() instanceof SwellGoal) goal.stop();
                creeper.setSwellDir(-1);
            }
        });
    }

    static void denyPickup(ItemEntityPickupEvent.Pre event, boolean campLease) {
        if (campLease) event.setCanPickup(TriState.FALSE);
    }
    static void denyXpPickup(PlayerXpEvent.PickupXp event, boolean campLease) {
        if (campLease) event.setCanceled(true);
    }
    static void denyTarget(LivingChangeTargetEvent event, boolean protectedTarget) {
        // Cancel would retain the old target. Null also prevents Brain StartAttacking.
        if (protectedTarget) event.setNewAboutToBeSetTarget(null);
    }
}
