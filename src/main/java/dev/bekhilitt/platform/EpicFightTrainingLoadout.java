package dev.bekhilitt.platform;

import dev.bekhilitt.platform.story.EpisodeCamp;
import dev.bekhilitt.platform.persistence.TrainingLeaseVerifier;
import java.io.IOException;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.GameRules;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Unbreakable;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.network.server.SPRemoveSkillAndLearn;
import yesman.epicfight.network.server.SPSkillFeedback;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.registry.entries.EpicFightSkills;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/**
 * A small reversible training lease stored with the player's own save.
 * No existing item or equipped skill is replaced. The lease is saved before the
 * first mutation, and survives both ordinary disconnects and interrupted worlds.
 */
public final class EpicFightTrainingLoadout {
    private static final String LEASE = TrainingLeaseVerifier.LEASE_KEY;
    private static final String PRACTICE = TrainingLeaseVerifier.PRACTICE_KEY;
    private static final String ITEM_OWNER = "bekhilitt_training_owner";
    private static final String ITEM_ATTEMPT = "bekhilitt_training_attempt";

    private EpicFightTrainingLoadout() { }

    public static void verifyReady(ServerPlayer player) {
        if (!player.getMainHandItem().isEmpty())
            throw new IllegalStateException("Выберите пустую ячейку панели перед запуском: она нужна для временного учебного двуручного меча.");
        if (!armorAndOffhandEmpty(player))
            throw new IllegalStateException("Снимите броню и освободите вторую руку перед учебным эпизодом: ваши вещи не должны терять прочность в тренировке.");
        if (EpicFightCapabilities.getServerPlayerPatch(player) == null)
            throw new IllegalStateException("Epic Fight не создал серверное состояние игрока. Эпизод не запущен.");
    }

    public static void equip(ServerPlayer player, UUID attemptId) {
        if (player.getPersistentData().contains(LEASE, 10)) restore(player);
        verifyReady(player);
        ServerPlayerPatch patch = EpicFightCapabilities.getServerPlayerPatch(player);
        SkillContainer guard = patch.getSkill(SkillSlots.GUARD);
        SkillContainer dodge = patch.getSkill(SkillSlots.DODGE);
        CompoundTag lease = new CompoundTag();
        lease.putString("owner", player.getUUID().toString());
        lease.putString("attempt", attemptId.toString());
        lease.putBoolean("addedGuard", guard.isEmpty());
        lease.putBoolean("addedDodge", dodge.isEmpty());
        lease.putBoolean("guardWasLearned", patch.getPlayerSkills().hasLearned(EpicFightSkills.GUARD.get()));
        lease.putBoolean("dodgeWasLearned", patch.getPlayerSkills().hasLearned(EpicFightSkills.ROLL.get()));
        lease.putString("mode", patch.getPlayerMode().name());
        lease.putFloat("health", player.getHealth());
        lease.putFloat("stamina", patch.getStamina());
        lease.putInt("food", player.getFoodData().getFoodLevel());
        lease.putFloat("saturation", player.getFoodData().getSaturationLevel());
        lease.putFloat("exhaustion", player.getFoodData().getExhaustionLevel());
        player.getPersistentData().put(LEASE, lease);
        // One vanilla player snapshot owns the lease, inventory, health and EF
        // skill data. Save both playerdata and integrated owner before changing it.
        player.getServer().saveEverything(true, true, true);
        try {
            TrainingLeaseVerifier.verify(player.getServer().getWorldPath(LevelResource.ROOT).normalize(), player.getUUID(),
                player.getServer().isDedicatedServer(), lease);
        } catch (IOException error) {
            throw new IllegalStateException("Запись возврата снаряжения не подтверждена; учебное оружие не выдано.", error);
        }

        ItemStack sword = new ItemStack(EpicFightItems.IRON_GREATSWORD.get());
        CompoundTag marker = new CompoundTag();
        marker.putString(ITEM_OWNER, player.getUUID().toString());
        marker.putString(ITEM_ATTEMPT, attemptId.toString());
        sword.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
        sword.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        sword.set(DataComponents.CUSTOM_NAME, Component.translatable("item.bekhilitt.training_greatsword"));
        player.getInventory().setItem(player.getInventory().selected, sword);
        if (guard.isEmpty()) { guard.setSkill(EpicFightSkills.GUARD.get()); sync(player, guard); }
        if (dodge.isEmpty()) { dodge.setSkill(EpicFightSkills.ROLL.get()); sync(player, dodge); }
        patch.toEpicFightMode(true);
        patch.setStamina(patch.getMaxStamina());
        player.setHealth(player.getMaxHealth());
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    public static void verifyPracticeReady(ServerPlayer player) {
        if (player.getServer().isDedicatedServer())
            throw new IllegalStateException("Практика навыков доступна только в одиночном одноразовом мире.");
        verifyReady(player);
        var patch = EpicFightCapabilities.getServerPlayerPatch(player);
        if (!patch.getSkill(SkillSlots.MOVER).isEmpty() || !patch.getSkill(SkillSlots.IDENTITY).isEmpty())
            throw new IllegalStateException("Для временной практики нужны пустые слоты Mover и Identity; существующие навыки не заменяются.");
    }

    public static void equipPractice(ServerPlayer player, UUID attemptId) {
        verifyPracticeReady(player);
        equip(player, attemptId);
        var patch = EpicFightCapabilities.getServerPlayerPatch(player);
        CompoundTag lease = new CompoundTag();
        lease.putInt("format", 1);
        lease.putString("owner", player.getUUID().toString());
        lease.putString("attempt", attemptId.toString());
        lease.putBoolean("leapWasLearned", patch.getPlayerSkills().hasLearned(EpicFightSkills.DEMOLITION_LEAP.get()));
        lease.putBoolean("slamWasLearned", patch.getPlayerSkills().hasLearned(EpicFightSkills.METEOR_SLAM.get()));
        lease.putBoolean("mobGriefing", player.getServer().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING));
        player.getPersistentData().put(PRACTICE, lease);
        player.getServer().saveEverything(true, true, true);
        try {
            TrainingLeaseVerifier.verifyPractice(player.getServer().getWorldPath(LevelResource.ROOT).normalize(), player.getUUID(), lease);
        } catch (IOException error) {
            throw new IllegalStateException("Возврат навыков и настройки мира не подтверждён; практика не включена.", error);
        }
        // Native Leap and Slam can remove non-solid blocks when this rule is true.
        // Persist the original rule first; never change it for the story duel.
        player.getServer().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, player.getServer());
        var mover = patch.getSkill(SkillSlots.MOVER);
        var identity = patch.getSkill(SkillSlots.IDENTITY);
        mover.setSkill(EpicFightSkills.DEMOLITION_LEAP.get()); sync(player, mover);
        identity.setSkill(EpicFightSkills.METEOR_SLAM.get()); sync(player, identity);
    }

    public static boolean hasPracticeLease(ServerPlayer player) {
        return player.getPersistentData().contains(PRACTICE);
    }

    /** Safe without parsing the lease: stops actions but never guesses prior state. */
    public static void quarantinePracticeActions(ServerPlayer player) {
        var patch = EpicFightCapabilities.getServerPlayerPatch(player);
        if (patch == null) return;
        // Expire on the client before SPChangeSkill(null) removes its container.
        // Otherwise EF can retain a held Leap whose later reset has no container.
        if (patch.getSkill(SkillSlots.MOVER).getSkill() == EpicFightSkills.DEMOLITION_LEAP.get())
            EpicFightNetworkManager.sendToPlayer(SPSkillFeedback.expired(SkillSlots.MOVER), player);
        patch.resetHolding();
        patch.playAnimationInstantly(Animations.BIPED_IDLE);
        patch.disableModelYRot(true);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.fallDistance = 0;
    }

    private static void restorePractice(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(PRACTICE)) return;
        if (!data.contains(PRACTICE, 10)) throw new IllegalStateException("Повреждён тип записи возврата практики.");
        CompoundTag lease = data.getCompound(PRACTICE);
        TrainingLeaseVerifier.validatePractice(player.getUUID(), lease);
        if (player.getServer().isDedicatedServer())
            throw new IllegalStateException("Одиночная запись практики не восстанавливается на выделенном сервере.");
        var patch = EpicFightCapabilities.getServerPlayerPatch(player);
        if (patch == null) throw new IllegalStateException("Epic Fight ещё не готов восстановить практику.");
        // Cancel held/native actions before restoring the world rule. In particular,
        // a queued Leap release must not fracture terrain after mobGriefing returns.
        patch.resetHolding();
        patch.playAnimationInstantly(Animations.BIPED_IDLE);
        patch.disableModelYRot(true);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.fallDistance = 0;
        var mover = patch.getSkill(SkillSlots.MOVER);
        var identity = patch.getSkill(SkillSlots.IDENTITY);
        if (mover.getSkill() == EpicFightSkills.DEMOLITION_LEAP.get()) { mover.setSkill(null); sync(player, mover); }
        if (identity.getSkill() == EpicFightSkills.METEOR_SLAM.get()) { identity.setSkill(null); sync(player, identity); }
        if (!lease.getBoolean("leapWasLearned")) {
            patch.getPlayerSkills().removeLearnedSkill(EpicFightSkills.DEMOLITION_LEAP.get());
            EpicFightNetworkManager.sendToPlayer(new SPRemoveSkillAndLearn(EpicFightSkills.DEMOLITION_LEAP.get().holder(), SkillSlots.MOVER), player);
        }
        if (!lease.getBoolean("slamWasLearned")) {
            patch.getPlayerSkills().removeLearnedSkill(EpicFightSkills.METEOR_SLAM.get());
            EpicFightNetworkManager.sendToPlayer(new SPRemoveSkillAndLearn(EpicFightSkills.METEOR_SLAM.get().holder(), SkillSlots.IDENTITY), player);
        }
        player.getServer().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(lease.getBoolean("mobGriefing"), player.getServer());
        player.getServer().saveEverything(true, true, true);
        try {
            TrainingLeaseVerifier.verifyPracticeRuleRestored(player.getServer().getWorldPath(LevelResource.ROOT).normalize(), player.getUUID(), lease);
        } catch (IOException error) {
            throw new IllegalStateException("Исходная настройка мира ещё не подтверждена на диске; запись возврата сохранена.", error);
        }
        data.remove(PRACTICE);
    }

    /** Safe to repeat; ordinary swords and pre-existing skills are never removed. */
    public static void restore(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        // A corrupt record must not leave a queued native Leap/Slam running.
        // Halting actions is safe without knowing the original rule or skill state.
        if (hasPracticeLease(player)) quarantinePracticeActions(player);
        // Validate both records before any restoration mutation.
        if (data.contains(LEASE) && !data.contains(LEASE, 10))
            throw new IllegalStateException("Повреждён тип записи возврата снаряжения.");
        if (data.contains(LEASE, 10)) TrainingLeaseVerifier.validate(player.getUUID(), data.getCompound(LEASE));
        restorePractice(player);
        if (!data.contains(LEASE, 10)) {
            removeTemporaryItems(player);
            return;
        }
        CompoundTag lease = data.getCompound(LEASE);
        TrainingLeaseVerifier.validate(player.getUUID(), lease);
        if (!player.getUUID().toString().equals(lease.getString("owner")))
            throw new IllegalStateException("Владелец временного боевого снаряжения не совпадает; восстановление остановлено.");
        ServerPlayerPatch patch = EpicFightCapabilities.getServerPlayerPatch(player);
        if (patch == null) throw new IllegalStateException("Epic Fight ещё не готов восстановить временные навыки.");
        PlayerPatch.PlayerMode mode;
        try { mode = PlayerPatch.PlayerMode.valueOf(lease.getString("mode")); }
        catch (IllegalArgumentException error) { throw new IllegalStateException("Повреждён режим временного боевого снаряжения.", error); }
        float health = lease.getFloat("health"), stamina = lease.getFloat("stamina");
        if (!Float.isFinite(health) || health <= 0 || !Float.isFinite(stamina) || stamina < 0
                || lease.getInt("food") < 0 || lease.getInt("food") > 20
                || !Float.isFinite(lease.getFloat("saturation")) || lease.getFloat("saturation") < 0
                || !Float.isFinite(lease.getFloat("exhaustion")) || lease.getFloat("exhaustion") < 0)
            throw new IllegalStateException("Повреждён снимок состояния игрока; временная запись сохранена.");

        removeTemporaryItems(player);
        SkillContainer guard = patch.getSkill(SkillSlots.GUARD);
        SkillContainer dodge = patch.getSkill(SkillSlots.DODGE);
        if (lease.getBoolean("addedGuard") && guard.getSkill() == EpicFightSkills.GUARD.get()) {
            guard.setSkill(null); sync(player, guard);
        }
        if (lease.getBoolean("addedDodge") && dodge.getSkill() == EpicFightSkills.ROLL.get()) {
            dodge.setSkill(null); sync(player, dodge);
        }
        if (lease.getBoolean("addedGuard") && !lease.getBoolean("guardWasLearned")) {
            patch.getPlayerSkills().removeLearnedSkill(EpicFightSkills.GUARD.get());
            EpicFightNetworkManager.sendToPlayer(new SPRemoveSkillAndLearn(EpicFightSkills.GUARD.get().holder(), SkillSlots.GUARD), player);
        }
        if (lease.getBoolean("addedDodge") && !lease.getBoolean("dodgeWasLearned")) {
            patch.getPlayerSkills().removeLearnedSkill(EpicFightSkills.ROLL.get());
            EpicFightNetworkManager.sendToPlayer(new SPRemoveSkillAndLearn(EpicFightSkills.ROLL.get().holder(), SkillSlots.DODGE), player);
        }
        patch.resetHolding();
        patch.playAnimationInstantly(Animations.BIPED_IDLE);
        patch.disableModelYRot(true);
        patch.toMode(mode, true);
        patch.setStamina(Math.min(stamina, patch.getMaxStamina()));
        if (player.isAlive()) player.setHealth(Math.min(health, player.getMaxHealth()));
        player.getFoodData().setFoodLevel(lease.getInt("food"));
        player.getFoodData().setSaturation(lease.getFloat("saturation"));
        player.getFoodData().setExhaustion(lease.getFloat("exhaustion"));
        data.remove(LEASE);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        // The caller subsequently saves and verifies its player-return lease.
        // If interrupted before that save, the old lease restores idempotently.
    }

    public static boolean temporary(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().contains(ITEM_OWNER, 8) && custom.copyTag().contains(ITEM_ATTEMPT, 8);
    }

    public static boolean validEquipment(ServerPlayer player) {
        return armorAndOffhandEmpty(player) && belongsTo(player.getMainHandItem(), player.getUUID());
    }
    public static boolean validPracticeEquipment(ServerPlayer player) {
        var patch = EpicFightCapabilities.getServerPlayerPatch(player);
        return validEquipment(player) && patch != null
            && patch.getSkill(SkillSlots.MOVER).getSkill() == EpicFightSkills.DEMOLITION_LEAP.get()
            && patch.getSkill(SkillSlots.IDENTITY).getSkill() == EpicFightSkills.METEOR_SLAM.get();
    }

    private static boolean armorAndOffhandEmpty(ServerPlayer player) {
        if (!player.getOffhandItem().isEmpty()) return false;
        for (ItemStack stack : player.getArmorSlots()) if (!stack.isEmpty()) return false;
        return true;
    }

    private static boolean belongsTo(ItemStack stack, UUID owner) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return temporary(stack) && owner.toString().equals(custom.copyTag().getString(ITEM_OWNER));
    }

    private static void removeTemporaryItems(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (belongsTo(player.getInventory().getItem(i), player.getUUID())) player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        if (belongsTo(player.containerMenu.getCarried(), player.getUUID())) player.containerMenu.setCarried(ItemStack.EMPTY);
        for (var level : player.getServer().getAllLevels()) {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, EpisodeCamp.cleanupBounds(level))) {
                if (belongsTo(item.getItem(), player.getUUID())) item.discard();
            }
        }
    }

    private static void sync(ServerPlayer player, SkillContainer container) {
        EpicFightNetworkManager.sendToPlayer(container.createSyncPacketToLocalPlayer(), player);
        EpicFightNetworkManager.sendToAllPlayerTrackingThisEntity(container.createSyncPacketToRemotePlayer(), player);
    }
}
