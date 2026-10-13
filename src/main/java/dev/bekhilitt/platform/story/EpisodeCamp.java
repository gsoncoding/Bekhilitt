package dev.bekhilitt.platform.story;

import java.util.Map;
import java.util.LinkedHashMap;
import java.io.IOException;
import dev.bekhilitt.platform.persistence.CampPlacementStore;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.mob.VindicatorPatch;
import yesman.epicfight.world.capabilities.entitypatch.HumanoidMobPatch;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.entity.ai.goal.AnimatedAttackGoal;
import yesman.epicfight.world.entity.ai.goal.CombatBehaviors;

/** Compact disposable set, placed only into air or its exact existing fixture blocks. */
public final class EpisodeCamp {
    public static final String TAG = "bekhilitt.episode";
    private final ServerLevel level;
    private final UUID campaign;
    private final EpisodeRegionLease region;
    private int floor = Integer.MIN_VALUE;
    private CampSafetyRules.Region safetyRegion;
    private Vindicator griffith;
    private ArmorStand casca;
    public EpisodeCamp(ServerLevel level, UUID campaign) {
        this.level = level; this.campaign = campaign; this.region = new EpisodeRegionLease(level);
    }
    public void beginLoading() { region.acquire(); }
    public boolean regionReady() { return region.ready(); }
    public Vindicator griffith() { return griffith; }
    public ArmorStand casca() { return casca; }
    public CampSafetyRules.Region safetyRegion() { return safetyRegion; }
    public boolean present() { return griffith != null && casca != null && griffith.isAlive() && casca.isAlive(); }
    public int floor() {
        if (floor == Integer.MIN_VALUE) throw new IllegalStateException("Camp placement is not ready");
        return floor;
    }
    public Vec3 recoveryView() { return at(-11.5, 2, -5.5); }
    public Vec3 recoveryCasca() { return at(-8.5, 2, -5.5); }
    public Vec3 wake() { return at(-9.5, 2, -4.5); }
    public Vec3 cascaPosition() { return at(-7.5, 1, -2.5); }
    public Vec3 griffithPosition() { return at(2.5, 1, -2.5); }
    public Vec3 duelPlayer() { return at(3.5, 1, 6.5); }
    public Vec3 duelEnemy() { return at(3.5, 1, 2.5); }
    private Vec3 at(double x, int y, double z) { return new Vec3(x, floor() + y, z); }
    public AABB bounds() {
        return new AABB(WarCampEnvironment.MIN_X, floor() - 1, WarCampEnvironment.MIN_Z,
            WarCampEnvironment.MAX_X + 1, floor() + WarCampEnvironment.MAX_Y_OFFSET + 1, WarCampEnvironment.MAX_Z + 1);
    }
    /** Used for owned-entity cleanup, including dropped items after an interrupted placement. */
    public static AABB cleanupBounds(ServerLevel level) {
        return new AABB(WarCampEnvironment.MIN_X - 32, level.getMinBuildHeight(), WarCampEnvironment.MIN_Z - 32,
            WarCampEnvironment.MAX_X + 33, level.getMaxBuildHeight(), WarCampEnvironment.MAX_Z + 33);
    }
    public boolean contains(Vec3 point) { return bounds().contains(point); }
    public boolean containsPractice(Vec3 point) {
        return point.x >= WarCampEnvironment.MIN_X && point.x <= WarCampEnvironment.MAX_X
            && point.z >= WarCampEnvironment.MIN_Z && point.z <= WarCampEnvironment.MAX_Z
            && point.y >= floor() - 1 && point.y <= Math.min(floor() + 64, level.getMaxBuildHeight() - 1);
    }
    public void prepare() {
        if (!region.ready()) throw new IllegalStateException("Сюжетный регион ещё не готов; NPC не размещены.");
        var placementStore = new CampPlacementStore(level.getServer().getWorldPath(LevelResource.ROOT).normalize());
        CampPlacementStore.Saved placement;
        Map<WarCampPlan.Cell, WarCampPlan.Material> relative = WarCampPlan.layout(level.getSeed());
        try {
            if (placementStore.exists()) {
                placement = placementStore.read();
                if (!placement.campaignId().equals(campaign) || placement.seed() != level.getSeed())
                    throw new IllegalStateException("Размещение лагеря принадлежит другой кампании или seed. Мир не изменён.");
            } else {
                var ground = new LinkedHashMap<WarCampFoundation.Column, Integer>();
                for (WarCampPlan.Cell cell : relative.keySet()) {
                    var column = new WarCampFoundation.Column(cell.x(), cell.z());
                    ground.computeIfAbsent(column, c -> level.getHeight(Heightmap.Types.WORLD_SURFACE, c.x(), c.z()));
                }
                int surface = ground.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
                placement = new CampPlacementStore.Saved(campaign, level.getSeed(), Math.addExact(surface, 6), ground);
            }
            floor = placement.floor();
            if (!WarCampPlan.fitsBuildHeight(floor, level.getMinBuildHeight(), level.getMaxBuildHeight()))
                throw new IllegalStateException("Над рельефом недостаточно высоты для безопасного лагеря. Выберите другой одноразовый мир.");
            Map<WarCampPlan.Cell, WarCampPlan.Material> foundation = WarCampFoundation.plan(relative, floor, placement.groundFirstAir());
            safetyRegion = CampSafetyRules.Region.at(floor,
                placement.groundFirstAir().values().stream().mapToInt(Integer::intValue).min().orElseThrow());
            var plan = new LinkedHashMap<BlockPos, BlockState>(WarCampEnvironment.layout(level.getSeed(), floor));
            foundation.forEach((cell, material) -> plan.put(new BlockPos(cell.x(), cell.y(), cell.z()),
                (material == WarCampPlan.Material.ANDESITE ? Blocks.ANDESITE : Blocks.STONE).defaultBlockState()));
            // Read the COMPLETE scenery volume and every support before changing a
            // block. Natural ground is preserved; foundations occupy air above it.
            for (int x = WarCampEnvironment.MIN_X; x <= WarCampEnvironment.MAX_X; x++)
                for (int z = WarCampEnvironment.MIN_Z; z <= WarCampEnvironment.MAX_Z; z++)
                    for (int y = floor + WarCampEnvironment.MIN_Y_OFFSET; y <= floor + WarCampEnvironment.MAX_Y_OFFSET; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        validateEmptyOrOwned(pos, plan.get(pos));
                    }
            plan.forEach(this::validateEmptyOrOwned);
            // Never resample a built camp's heightmap on resume. This immutable,
            // verified placement record is durable before the first world edit.
            placementStore.write(placement);
            if (!placement.equals(placementStore.read())) throw new IOException("Camp placement verification failed");
            plan.forEach((pos, block) -> {
                if (level.getBlockState(pos).isAir() && !level.setBlock(pos, block, 3))
                    throw new IllegalStateException("Не удалось безопасно разместить декорацию: " + pos.toShortString());
            });
        } catch (IOException error) {
            throw new IllegalStateException("Размещение лагеря не сохранено или повреждено; повторная генерация запрещена.", error);
        }
        cleanOrphans();
        griffith = combatActor();
        casca = actor(cascaPosition(), "character.bekhilitt.casca", false);
    }
    private void validateEmptyOrOwned(BlockPos pos, BlockState expected) {
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
            throw new IllegalStateException("План лагеря вышел за границы мира. Ничего не размещено.");
        BlockState actual = level.getBlockState(pos);
        if (!actual.isAir() && (expected == null || !actual.is(expected.getBlock())))
            throw new IllegalStateException("Место занято чужими блоками: " + pos.toShortString() + ". Ничего не заменено.");
    }
    private Vindicator combatActor() {
        Vindicator actor = EntityType.VINDICATOR.create(level);
        if (actor == null) throw new IllegalStateException("Не удалось создать боевого NPC.");
        Vec3 position = griffithPosition();
        actor.moveTo(position.x, position.y, position.z, 180, 0);
        actor.addTag(TAG); actor.addTag(campaign.toString());
        actor.setCustomName(Component.translatable("character.bekhilitt.griffith"));
        actor.setCustomNameVisible(true);
        actor.setPersistenceRequired();
        actor.setCanJoinRaid(false);
        actor.setCanPickUpLoot(false);
        actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80.0);
        actor.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(2.0);
        actor.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.25);
        actor.setHealth(actor.getMaxHealth());
        actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        actor.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        actor.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        actor.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) actor.setDropChance(slot, 0.0F);
        // EF installs native AnimatedAttackGoal on join only while NoAI is false.
        // Freeze immediately afterwards, before the world gets its first tick.
        if (!level.addFreshEntity(actor)) throw new IllegalStateException("Не удалось разместить боевого NPC.");
        VindicatorPatch<?> patch = EpicFightCapabilities.getEntityPatch(actor, VindicatorPatch.class);
        if (patch == null) {
            actor.discard();
            throw new IllegalStateException("Epic Fight не подключил VindicatorPatch; подменный ванильный бой запрещён.");
        }
        actor.setNoAi(true);
        actor.setInvulnerable(true);
        return actor;
    }
    private static void installTrainingBehavior(Vindicator actor) {
        VindicatorPatch<?> patch = EpicFightCapabilities.getEntityPatch(actor, VindicatorPatch.class);
        if (patch == null) throw new IllegalStateException("Epic Fight потерял боевое состояние NPC.");
        // Scope the training cadence to this disposable actor. Keep EF's own
        // chase goal, animator, colliders, native damage and interruption rules.
        // The default Vindicator loops three swings forever; a heavy greatsword
        // learner needs a readable windup and a real opening after a finite series.
        actor.goalSelector.removeAllGoals(goal -> goal instanceof AnimatedAttackGoal<?>);
        var behavior = CombatBehaviors.<HumanoidMobPatch<?>>builder()
            .newBehaviorSeries(CombatBehaviors.BehaviorSeries.<HumanoidMobPatch<?>>builder()
                .weight(1.0F).looping(false).canBeInterrupted(true).cooldown(50)
                .nextBehavior(CombatBehaviors.Behavior.<HumanoidMobPatch<?>>builder()
                    .animationBehavior(Animations.BIPED_MOB_ONEHAND1).withinEyeHeight().withinDistance(0, 2.7))
                .nextBehavior(CombatBehaviors.Behavior.<HumanoidMobPatch<?>>builder()
                    .animationBehavior(Animations.BIPED_MOB_ONEHAND2).withinEyeHeight().withinDistance(0, 2.7)))
            .build(patch);
        actor.goalSelector.addGoal(0, new AnimatedAttackGoal<HumanoidMobPatch<?>>(patch, behavior));
    }
    public void beginCombat(ServerPlayer player) {
        if (!present()) throw new IllegalStateException("Участники сцены недоступны.");
        // Equip-change notifications may reinitialize EF goals on the first mob
        // tick. Install only after the preparation period, immediately before AI.
        installTrainingBehavior(griffith);
        griffith.setHealth(griffith.getMaxHealth());
        griffith.setInvulnerable(false);
        griffith.setNoAi(false);
        griffith.setTarget(player);
        griffith.setAggressive(true);
    }
    public void stopCombat() {
        if (griffith == null || griffith.isRemoved()) return;
        griffith.setTarget(null);
        griffith.setAggressive(false);
        griffith.getNavigation().stop();
        griffith.setNoAi(true);
        griffith.setInvulnerable(true);
        griffith.setDeltaMovement(Vec3.ZERO);
    }
    private ArmorStand actor(Vec3 pos, String name, boolean commander) {
        ArmorStand actor = new ArmorStand(level, pos.x, pos.y, pos.z);
        actor.setShowArms(true); actor.setNoGravity(true); actor.setInvulnerable(true);
        actor.addTag(TAG); actor.addTag(campaign.toString());
        actor.setCustomName(Component.translatable(name)); actor.setCustomNameVisible(true);
        actor.setItemSlot(EquipmentSlot.CHEST, new ItemStack(commander ? Items.IRON_CHESTPLATE : Items.LEATHER_CHESTPLATE));
        actor.setItemSlot(EquipmentSlot.LEGS, new ItemStack(commander ? Items.IRON_LEGGINGS : Items.LEATHER_LEGGINGS));
        actor.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        if (commander) actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        if (!level.addFreshEntity(actor)) throw new IllegalStateException("Не удалось разместить сюжетного NPC; попытка остановлена.");
        return actor;
    }
    public void cleanOrphans() {
        for (LivingEntity actor : level.getEntitiesOfClass(LivingEntity.class, cleanupBounds(level)))
            if (actor.getTags().contains(TAG) && actor.getTags().contains(campaign.toString()) && actor != griffith && actor != casca) actor.discard();
    }
    public void close() {
        try {
            if (griffith != null) griffith.discard();
            if (casca != null) casca.discard();
        } finally {
            griffith = null; casca = null;
            region.close();
        }
    }
}
