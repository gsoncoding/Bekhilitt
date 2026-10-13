package dev.bekhilitt.platform;

import dev.bekhilitt.campaign.CampaignService;
import dev.bekhilitt.campaign.CampVisit;
import dev.bekhilitt.campaign.CampaignSnapshot;
import dev.bekhilitt.campaign.CampaignSnapshot.Interruption;
import dev.bekhilitt.campaign.CampaignSnapshot.Stage;
import dev.bekhilitt.combat.EpicDuelAttempt;
import dev.bekhilitt.combat.SkillPracticeAttempt;
import net.minecraft.world.level.GameRules;
import dev.bekhilitt.combat.TrainingCombatRules;
import dev.bekhilitt.combat.PracticeRecovery;
import dev.bekhilitt.platform.persistence.EpisodeStore;
import dev.bekhilitt.platform.persistence.OwnerReturnVerifier;
import dev.bekhilitt.platform.story.EpisodeCamp;
import dev.bekhilitt.platform.story.EpisodeContent;
import dev.bekhilitt.platform.story.EpisodeLoadGate;
import dev.bekhilitt.platform.story.CampSafetyRules;
import dev.bekhilitt.protocol.ArenaStatePayload;
import dev.bekhilitt.protocol.CombatStatusPayload;
import dev.bekhilitt.protocol.EpisodeAdvancePayload;
import dev.bekhilitt.protocol.EpisodeStatePayload;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import dev.bekhilitt.platform.networking.Network;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.api.utils.AttackResult;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Single-participant integrated/disposable-dedicated server adapter. Campaign state, cutscene timing, dialogue and combat are server owned. */
public final class FirstEpisode {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FirstEpisode.class);
    private EpisodeStore store;
    private CampaignService campaign;
    private EpisodeStore.ReturnPoint returnPoint;
    private EpisodeContent content;
    private Session session;
    private String blocked;
    private boolean epicHooksRegistered;
    private final Map<UUID, EpisodeLoadGate> pendingJoins = new HashMap<>();
    private long networkRevision; // Connection presentation token; independent of persisted campaign revisions.
    private static final class Session {
        final UUID owner;
        final ServerLevel level;
        final EpisodeCamp camp;
        final EpisodeLoadGate loading = new EpisodeLoadGate();
        EpicDuelAttempt duel;
        SkillPracticeAttempt practice;
        CampVisit visit;
        EpicDuelAttempt.Phase presentedPhase;
        int ticks, page = -1;
        // Bounded diagnostics for the disposable test profile; no input or health changes.
        final Map<String, Integer> skillDiagnostics = new HashMap<>();
        int attackDiagnostics, damageDiagnostics, geometryDiagnostics;
        Stage stage;
        Session(UUID owner, ServerLevel level, EpisodeCamp camp) { this.owner = owner; this.level = level; this.camp = camp; }
    }
    public boolean isParticipant(UUID id) { return session != null && session.owner.equals(id); }
    /** No protection of unrelated levels, ordinary play, story duels or standalone practice. */
    public CampSafetyRules.Region activeCampSafety(Level level) {
        if (session == null || !CampSafetyRules.active(true, session.visit != null,
                session.loading.state() == EpisodeLoadGate.State.READY, session.level == level)) return null;
        return session.camp.safetyRegion();
    }
    public boolean protectsCampActor(Entity target) {
        if (target == null || activeCampSafety(target.level()) == null) return false;
        return CampSafetyRules.protectsActor(true, session.owner,
            session.camp.griffith() == null ? null : session.camp.griffith().getUUID(),
            session.camp.casca() == null ? null : session.camp.casca().getUUID(), target.getUUID());
    }
    public boolean blocksInteraction(UUID id) { return isParticipant(id); }
    /** Includes non-living items/decor hit by Meteor's native area damage. */
    public boolean protectPracticeEntity(Entity victim, DamageSource source) {
        if (victim instanceof ServerPlayer p && CampPlayerLease.exists(p)) return true;
        if (source.getEntity() instanceof ServerPlayer p && CampPlayerLease.exists(p)) return true;
        boolean victimLeased = victim instanceof ServerPlayer player && EpicFightTrainingLoadout.hasPracticeLease(player);
        boolean attackerLeased = source.getEntity() instanceof ServerPlayer player && EpicFightTrainingLoadout.hasPracticeLease(player);
        boolean livePracticeInvolved = session != null && session.practice != null && (session.owner.equals(victim.getUUID())
            || (source.getEntity() != null && session.owner.equals(source.getEntity().getUUID())));
        return TrainingCombatRules.protectPracticeDamage(victimLeased, attackerLeased, livePracticeInvolved);
    }
    public boolean blocksPracticeRuleChange(MinecraftServer server) {
        return session != null && session.practice != null
            || server.getPlayerList().getPlayers().stream().anyMatch(EpicFightTrainingLoadout::hasPracticeLease);
    }
    private boolean practiceSafe(ServerPlayer player) {
        return session != null && session.practice != null && session.practice.active()
            && session.loading.state() == EpisodeLoadGate.State.READY
            && player.level() == session.level && session.camp.containsPractice(player.position())
            && !player.getServer().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            && EpicFightTrainingLoadout.validPracticeEquipment(player);
    }
    public boolean blocksAttack(UUID id, Entity target) {
        boolean canceled = isParticipant(id) && !(session.duel != null && session.duel.fighting()
            && target != null && session.duel.opponentId().equals(target.getUUID()));
        if (isParticipant(id) && session.duel != null && target != null
                && session.duel.opponentId().equals(target.getUUID()) && diagnosticProfile()
                && session.attackDiagnostics++ < 20)
            LOGGER.info("Bekhilitt combat diagnostic: attackEntity attempt={} phase={} canceled={} targetInvulnerable={}",
                session.duel.attemptId(), session.duel.phase(), canceled, target.isInvulnerable());
        return canceled;
    }
    private static boolean testProfileEnabled(MinecraftServer server) {
        return Boolean.getBoolean("bekhilitt.dev")
            && (!server.isDedicatedServer() || Boolean.getBoolean("bekhilitt.dedicatedTest"));
    }
    private static boolean levelDifficultyIsPeaceful(MinecraftServer server) {
        return server.overworld().getDifficulty() == Difficulty.PEACEFUL;
    }

    /** Called once during common initialization; all EF hooks used here are server-sided. */
    public void registerEpicHooks() {
        if (epicHooksRegistered) return;
        epicHooksRegistered = true;
        // GuardSkill cancels the ordinary listener chain after resolving BLOCKED.
        // A context-aware final observer sees that result without changing it.
        EpicFightEventHooks.Entity.TAKE_DAMAGE_INCOME.registerContextAwareEvent((event, context) -> {
            if (session == null || session.duel == null || !(event.getDamageSource() instanceof EpicFightDamageSource)) return;
            Entity source = event.getDamageSource().getEntity();
            diagnoseDamage("epicIncome", event.getEntityPatch().getOriginal(), event.getDamageSource(), event.getDamage(), event.isCanceled());
            if (source != null && event.isCanceled() && event.getResult() == AttackResult.ResultType.BLOCKED)
                session.duel.observeGuard(source.getUUID(), event.getEntityPatch().getOriginal().getUUID());
        }, "bekhilitt:training_guard_observer", Integer.MIN_VALUE);
        EpicFightEventHooks.Entity.ON_DODGE.registerEvent(event -> {
            if (session == null || session.duel == null || !(event.getDamageSource() instanceof EpicFightDamageSource)) return;
            Entity source = event.getDamageSource().getEntity();
            if (source != null) session.duel.observeDodge(source.getUUID(), event.getEntityPatch().getOriginal().getUUID());
        }, "bekhilitt:training_dodge_observer");
        EpicFightEventHooks.Player.CAST_SKILL.registerEvent(event -> {
            if (!(event.getPlayerPatch().getOriginal() instanceof ServerPlayer player)) return;
            if (TrainingCombatRules.quarantinePractice(EpicFightTrainingLoadout.hasPracticeLease(player),
                    isParticipant(player.getUUID()) && practiceSafe(player))) {
                EpicFightTrainingLoadout.quarantinePracticeActions(player);
                event.cancel(); return;
            }
            if (!isParticipant(player.getUUID())) return;
            var slot = event.getSkillContainer().getSlot();
            boolean tutorialSkill = slot == SkillSlots.COMBO_ATTACKS || slot == SkillSlots.GUARD
                || slot == SkillSlots.DODGE || slot == SkillSlots.KNOCKDOWN_WAKEUP;
            if (session.practice != null) {
                if (!practiceSafe(player) || !(tutorialSkill || slot == SkillSlots.MOVER || slot == SkillSlots.IDENTITY)) event.cancel();
                return;
            }
            if (session.duel == null || !session.duel.fighting() || !tutorialSkill
                    || !EpicFightTrainingLoadout.validEquipment(player)) event.cancel();
        }, "bekhilitt:episode_skill_boundary", Integer.MAX_VALUE);
        EpicFightEventHooks.Player.CAST_SKILL.registerContextAwareEvent((event, context) -> {
            if (!(event.getPlayerPatch().getOriginal() instanceof ServerPlayer player)
                    || !isParticipant(player.getUUID()) || session.duel == null || !diagnosticProfile()) return;
            String slot = event.getSkillContainer().getSlot().toString();
            int count = session.skillDiagnostics.merge(slot, 1, Integer::sum);
            if (count <= 12) {
                if (slot.equals("COMBO_ATTACKS")) diagnoseGeometry("cast", event.getPlayerPatch(), null);
                LOGGER.info("Bekhilitt combat diagnostic: cast attempt={} phase={} slot={} skill={} canceled={} skillExecutable={} stateExecutable={} equipment={} mode={} stamina={}",
                    session.duel.attemptId(), session.duel.phase(), slot, event.getSkillContainer().getSkill(), event.isCanceled(),
                    event.isSkillExecutable(), event.isStateExecutable(), EpicFightTrainingLoadout.validEquipment(player),
                    event.getPlayerPatch().getPlayerMode(), event.getPlayerPatch().getStamina());
            }
        }, "bekhilitt:bounded_skill_diagnostic", Integer.MIN_VALUE);
        EpicFightEventHooks.Animation.BEGIN.registerEvent(event -> {
            if (event.getEntityPatch().getOriginal() instanceof ServerPlayer player && isParticipant(player.getUUID())
                    && session.practice != null && practiceSafe(player)) {
                boolean changed = event.getAnimation() == Animations.BIPED_DEMOLITION_LEAP
                    ? session.practice.observeLeap(player.getUUID())
                    : event.getAnimation() == Animations.METEOR_SLAM && session.practice.observeSlam(player.getUUID());
                if (changed) {
                    LOGGER.info("Bekhilitt native practice observed: attempt={} leap={} slam={} animation={}", session.practice.attemptId(),
                        session.practice.leapObserved(), session.practice.slamObserved(), event.getAnimation().registryName());
                    message(player, session.practice.allObserved() ? "Оба нативных навыка выполнены. Можно продолжить практику или /bekhilitt stop."
                        : "Нативный навык выполнен: " + (session.practice.leapObserved() ? "Demolition Leap" : "Meteor Slam") + ".");
                }
            }
            if (event.getAnimation().get() instanceof yesman.epicfight.api.animation.types.AttackAnimation)
                diagnoseGeometry("attackBegin", event.getEntityPatch(), event.getAnimation().registryName());
        }, "bekhilitt:bounded_attack_begin_diagnostic");
        EpicFightEventHooks.Animation.END.registerEvent(event -> {
            if (event.getAnimation().get() instanceof yesman.epicfight.api.animation.types.AttackAnimation)
                diagnoseGeometry("attackEnd natural=" + event.isEnd(), event.getEntityPatch(), event.getAnimation().registryName());
        }, "bekhilitt:bounded_attack_end_diagnostic");
        EpicFightEventHooks.Animation.ATTACK_PHASE_END.registerEvent(event ->
            diagnoseGeometry("contactEnd terminated=" + event.isAnimationTerminated(), event.getEntityPatch(), event.getAnimation().registryName()),
            "bekhilitt:bounded_attack_contact_diagnostic");
    }

    private void diagnoseGeometry(String point, LivingEntityPatch<?> patch, Object animation) {
        if (!(patch.getOriginal() instanceof ServerPlayer player) || !isParticipant(player.getUUID())
                || session.duel == null || !session.duel.fighting() || !diagnosticProfile()
                || session.geometryDiagnostics++ >= 60) return;
        var enemy = session.camp.griffith();
        var animator = patch.getAnimator().getPlayerFor(null);
        LOGGER.info("Bekhilitt combat diagnostic: {} attempt={} animation={} current={} elapsed={} distance={} playerPos={} enemyPos={} yaw={} modelYaw={} pitch={} onGround={} attacking={} inaction={} targetMatches={} enemyInvulnerable={} nativeTargetRejected={}",
            point, session.duel.attemptId(), animation, animator.getRealAnimation().registryName(), animator.getElapsedTime(),
            player.distanceTo(enemy), player.position(), enemy.position(), player.getYRot(), patch.getYRot(), player.getXRot(),
            player.onGround(), patch.getEntityState().attacking(), patch.getEntityState().inaction(), patch.getTarget() == enemy,
            enemy.isInvulnerable(), patch.isTargetInvulnerable(enemy));
    }

    private boolean diagnosticProfile() {
        return session != null && testProfileEnabled(session.level.getServer())
            && Boolean.getBoolean("bekhilitt.combatDiagnostics");
    }

    private void diagnoseDamage(String point, LivingEntity victim, DamageSource source, float amount, boolean canceled) {
        if (session == null || session.duel == null || !diagnosticProfile()
                || !session.duel.opponentId().equals(victim.getUUID()) || source.getEntity() == null
                || !session.owner.equals(source.getEntity().getUUID()) || session.damageDiagnostics++ >= 60) return;
        LOGGER.info("Bekhilitt combat diagnostic: {} attempt={} phase={} sourceClass={} amount={} canceled={} targetInvulnerable={} health={}",
            point, session.duel.attemptId(), session.duel.phase(), source.getClass().getName(), amount, canceled,
            victim.isInvulnerable(), victim.getHealth());
    }

    /** Only the live opponent/player pair may hurt each other, through native EF attacks. */
    public boolean cancelDamage(LivingEntity victim, DamageSource source) {
        if (victim instanceof ServerPlayer p && CampPlayerLease.exists(p)) return true;
        if (source.getEntity() instanceof ServerPlayer p && CampPlayerLease.exists(p)) return true;
        if (protectPracticeEntity(victim, source)) return true;
        if (session == null) return false;
        if (session.practice != null) return protectPracticeEntity(victim, source);
        UUID opponent = session.camp.griffith() == null ? null : session.camp.griffith().getUUID();
        Entity attacker = source.getEntity();
        if ((session.owner.equals(victim.getUUID()) || (attacker != null && session.owner.equals(attacker.getUUID())))
                && session.duel != null && session.duel.fighting()) {
            ServerPlayer player = session.level.getServer().getPlayerList().getPlayer(session.owner);
            if (player == null || !EpicFightTrainingLoadout.validEquipment(player)) {
                diagnoseDamage("damageBoundaryEquipment", victim, source, -1, true);
                return true;
            }
        }
        boolean canceled = TrainingCombatRules.cancelDamage(session.owner, opponent, session.duel != null && session.duel.fighting(),
            victim.getUUID(), attacker == null ? null : attacker.getUUID(), source instanceof EpicFightDamageSource);
        diagnoseDamage("damageBoundary", victim, source, -1, canceled);
        return canceled;
    }

    /** Called after native armor/potion calculation. Never lets native damage kill a participant. */
    public float limitDamage(LivingEntity victim, DamageSource source, float amount) {
        if (!ownedCombatant(victim)) return amount;
        diagnoseDamage("neoForgePre", victim, source, amount, false);
        if (cancelDamage(victim, source) || !Float.isFinite(amount)) return 0;
        return session.owner.equals(victim.getUUID())
            ? TrainingCombatRules.trainingPlayerDamage(amount, victim.getHealth())
            : TrainingCombatRules.nonlethalDamage(amount, victim.getHealth());
    }

    /** Final applied damage only; an attempted swing is not evidence of a landed attack. */
    public void damaged(LivingEntity victim, DamageSource source, float amount) {
        diagnoseDamage("neoForgePost", victim, source, amount, false);
        if (session == null || session.duel == null || !(source instanceof EpicFightDamageSource)) return;
        Entity attacker = source.getEntity();
        if (attacker == null || !session.duel.observeDamage(attacker.getUUID(), victim.getUUID(), amount)) return;
        if (victim.getHealth() <= 2.01F) session.duel.exhausted(victim.getUUID());
    }

    /** Final backstop for bypass-damage death paths such as /kill; not a story victory signal. */
    public boolean preventDeath(LivingEntity victim) {
        if (!ownedCombatant(victim)
                && !(victim instanceof ServerPlayer player && (EpicFightTrainingLoadout.hasPracticeLease(player) || CampPlayerLease.exists(player)))) return false;
        victim.setHealth(Math.max(2.0F, victim.getHealth()));
        return true;
    }

    private boolean ownedCombatant(LivingEntity entity) {
        return session != null && (session.owner.equals(entity.getUUID())
            || (session.camp.griffith() != null && session.camp.griffith().getUUID().equals(entity.getUUID())));
    }
    public int command(CommandSourceStack source, String action) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        MinecraftServer server = source.getServer();
        if (!testProfileEnabled(server)) {
            message(player, "Эпизод доступен только в отдельном тестовом профиле с -Dbekhilitt.dev=true. Для выделенного локального сервера также нужен -Dbekhilitt.dedicatedTest=true."); return 0;
        }
        if (action.equals("stop")) { if (isParticipant(player.getUUID())) { stop(server, player, Interruption.CANCELLED, false); return 1; } return 0; }
        if (action.equals("status")) {
            if (!load(server, player)) return 0;
            message(player, campaign == null ? "Эпизод не начат. /bekhilitt start покажет безопасный порядок запуска."
                : "Контрольная точка: " + campaign.snapshot().checkpointNodeId() + "; " + campaign.snapshot().status()
                    + "; ревизия " + campaign.snapshot().revision() + "."); return 1;
        }
        if (action.equals("camp")) return startCamp(server, player);
        if (action.equals("practice")) {
            // End a visit through its verified return boundary before lending practice state.
            if (isParticipant(player.getUUID()) && session.visit != null) stop(server, player, Interruption.CANCELLED, false);
            if (CampPlayerLease.exists(player)) { message(player, "Возврат из лагеря ещё не завершён."); return 0; }
            return startPractice(server, player);
        }
        if (action.equals("start") || action.equals("start_disposable") || action.equals("resume")) return start(server, player, action);
        if (!isParticipant(player.getUUID())) return 0;
        if (session.loading.state() != EpisodeLoadGate.State.READY) return 0;
        if (action.equals("talk")) {
            if (session.visit != null) {
                Entity nearest = player.distanceTo(session.camp.casca()) < player.distanceTo(session.camp.griffith())
                    ? session.camp.casca() : session.camp.griffith();
                interactCamp(player, nearest);
            } else openDialogue(player);
            return 1;
        }
        message(player, "В бою используйте собственные клавиши Epic Fight: удар, защита и уклонение. Команды не заменяют нативный ввод.");
        return 0;
    }
    private boolean load(MinecraftServer server, ServerPlayer player) {
        if (blocked != null) { message(player, blocked); return false; }
        if (store != null) return true;
        store = new EpisodeStore(server.getWorldPath(LevelResource.ROOT).normalize());
        if (!store.exists()) return true;
        try {
            EpisodeStore.Saved saved = store.read();
            campaign = CampaignService.restore(saved.campaign()); returnPoint = saved.returnPoint();
            if (!campaign.snapshot().equals(saved.campaign())) persist();
            return true;
        } catch (IOException | RuntimeException error) {
            blocked = "Сохранение кампании не читается. Оно не сброшено и не перезаписано. Восстановите резервную копию при закрытом мире. " + error.getMessage();
            message(player, blocked); return false;
        }
    }
    private int start(MinecraftServer server, ServerPlayer player, String action) {
        // Must precede load/create and every raw training restore: a missing or
        // incomplete campaign cannot turn /start disposable or /resume into a
        // bypass of the verified practice-return boundary.
        if (CampPlayerLease.exists(player)) { message(player, "Сначала требуется восстановление посещения лагеря; повторите вход."); return 0; }
        try { PracticeRecovery.requireStoryStartAllowed(EpicFightTrainingLoadout.hasPracticeLease(player)); }
        catch (IllegalStateException error) { message(player, error.getMessage()); return 0; }
        if (pendingJoins.containsKey(player.getUUID())) {
            message(player, "Сначала восстанавливается состояние после входа. Повторите команду через несколько секунд."); return 0;
        }
        if (session != null || server.getPlayerList().getPlayerCount() != 1) {
            message(player, "Эпизод рассчитан на одного игрока; активная сцена уже запущена или подключён другой игрок."); return 0;
        }
        if (!load(server, player)) return 0;
        if (campaign == null && !action.equals("start_disposable")) {
            message(player, "Создайте отдельный одноразовый мир с именем Bekhilitt-Test. Команда /bekhilitt start disposable разместит тестовый лагерь только в свободном объёме. Не используйте ценный мир."); return 0;
        }
        String worldName = server.getWorldData().getLevelName().toLowerCase(Locale.ROOT);
        if (!worldName.startsWith("bekhilitt-test")) {
            message(player, "Безопасный запуск требует отдельного мира с именем Bekhilitt-Test (можно добавить суффикс). Этот мир не изменён."); return 0;
        }
        if (campaign != null && !campaign.snapshot().participantId().equals(player.getUUID())) {
            message(player, "У этой одиночной кампании другой участник. Сохранение не изменено."); return 0;
        }
        if (campaign != null && campaign.snapshot().complete()) {
            return startCamp(server, player);
        }
        try {
            if (levelDifficultyIsPeaceful(server)) throw new IllegalStateException("Для боевого NPC нужна сложность Easy, Normal или Hard. Мир не изменён.");
            EpicFightTrainingLoadout.restore(player);
            EpicFightTrainingLoadout.verifyReady(player);
            if (content == null) content = EpisodeContent.load();
            if (returnPoint != null && returnPoint.pending()) restorePlayer(server, player, true);
            EpisodeStore.ReturnPoint nextReturn = capture(player); // Validate before changing campaign state.
            if (campaign == null) campaign = CampaignService.create(UUID.randomUUID(), player.getUUID());
            else campaign.resume(player.getUUID());
            returnPoint = nextReturn;
            persist(); // Persist the return lease before moving/changing the player's game mode.
            ServerLevel level = server.overworld();
            EpisodeCamp camp = new EpisodeCamp(level, campaign.snapshot().campaignId());
            session = new Session(player.getUUID(), level, camp);
            sendInactive(player);
            camp.beginLoading();
            message(player, "Загрузка тестового лагеря… /bekhilitt stop — отменить, сохранив контрольную точку.");
            return 1;
        } catch (IOException | RuntimeException error) {
            message(player, "Запуск остановлен: " + error.getMessage());
            if (session != null) stop(server, player, Interruption.PLATFORM_FAILURE, false);
            else if (campaign != null && !campaign.snapshot().complete()) {
                campaign.interrupt(player.getUUID(), campaign.snapshot().attemptId(), Interruption.PLATFORM_FAILURE);
                try { persist(); } catch (IOException | RuntimeException ignored) { /* Preserve the original failure. */ }
            }
            return 0;
        }
    }
    private int startCamp(MinecraftServer server, ServerPlayer player) {
        if (server.isDedicatedServer() || session != null || pendingJoins.containsKey(player.getUUID())
                || server.getPlayerList().getPlayerCount() != 1) {
            message(player, "Лагерь: сначала завершите активную сцену или восстановление в одиночном мире."); return 0;
        }
        if (!load(server, player)) return 0;
        try {
            CampVisit.requireCompletedOwner(campaign == null ? null : campaign.snapshot(), player.getUUID());
            if (!server.getWorldData().getLevelName().toLowerCase(Locale.ROOT).startsWith("bekhilitt-test"))
                throw new IllegalStateException("Для лагеря нужен отдельный мир Bekhilitt-Test*.");
            if (CampPlayerLease.exists(player)) restoreCampParticipant(server, player, true);
            if (EpicFightTrainingLoadout.hasPracticeLease(player)) restorePracticeParticipant(server, player, true);
            else {
                EpicFightTrainingLoadout.restore(player);
                if (returnPoint.pending()) restorePlayer(server, player, true);
            }
            if (levelDifficultyIsPeaceful(server)) throw new IllegalStateException("Для временного NPC нужна сложность не Peaceful.");
            returnPoint = capture(player);
            persist(); // Always keep the original return point, not an in-camp location.
            CampPlayerLease.capture(player);
            session = new Session(player.getUUID(), server.overworld(), new EpisodeCamp(server.overworld(), campaign.snapshot().campaignId()));
            session.visit = new CampVisit(campaign.snapshot(), player.getUUID());
            session.stage = Stage.END;
            sendInactive(player);
            session.camp.beginLoading();
            message(player, "Загрузка свободного лагеря. /bekhilitt stop — безопасный выход.");
            return 1;
        } catch (IOException | RuntimeException error) {
            message(player, "Вход в лагерь остановлен: " + error.getMessage());
            if (session != null) stop(server, player, Interruption.PLATFORM_FAILURE, false);
            return 0;
        }
    }
    private void enterCamp(ServerPlayer player) throws IOException {
        Session s = session;
        CampVisit.requireCompletedOwner(campaign.snapshot(), player.getUUID());
        s.camp.stopCombat();
        // The END transition restored the duel equipment before capturing camp vitals.
        if (!CampPlayerLease.exists(player)) CampPlayerLease.capture(player);
        s.visit = new CampVisit(campaign.snapshot(), player.getUUID());
        s.duel = null; s.page = -1; s.stage = Stage.END;
        s.camp.griffith().moveTo(s.camp.griffithPosition().x, s.camp.griffithPosition().y, s.camp.griffithPosition().z, 180, 0);
        s.camp.casca().moveTo(s.camp.cascaPosition().x, s.camp.cascaPosition().y, s.camp.cascaPosition().z, 135, 0);
        if (player.level() != s.level || !s.camp.contains(player.position())) move(player, s.level, s.camp.wake(), -45, 0);
        player.setGameMode(GameType.ADVENTURE);
        Network.send(player, new ArenaStatePayload(0));
        sendPresentation(player);
        message(player, "Свободный лагерь: ПКМ по Каске или Гриффиту — разговор. /bekhilitt practice — практика. /bekhilitt stop — выход; /bekhilitt camp — вернуться. Сюжет завершён, новых наград нет.");
    }
    public void interactCamp(ServerPlayer player, Entity target) {
        if (!isParticipant(player.getUUID()) || session.visit == null || session.loading.state() != EpisodeLoadGate.State.READY) return;
        CampVisit.Speaker speaker = target == session.camp.casca() ? CampVisit.Speaker.CASCA
            : target == session.camp.griffith() ? CampVisit.Speaker.GRIFFITH : null;
        if (speaker == null || !session.visit.open(speaker, player.distanceTo(target), player.hasLineOfSight(target))) return;
        Vec3 look = target.getEyePosition().subtract(player.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(-look.x,look.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(look.y,Math.sqrt(look.x*look.x+look.z*look.z)));
        move(player,session.level,player.position(),yaw,pitch);
        target.setYRot((float)Math.toDegrees(Math.atan2(look.x,-look.z)));
        sendPresentation(player);
    }
    private void closeCampTalk(ServerPlayer player) {
        session.visit.close(); sendPresentation(player);
    }
    private void restoreCampParticipant(MinecraftServer server, ServerPlayer player, boolean finishReturn) throws IOException {
        boolean trusted = blocked == null && !server.isDedicatedServer() && campaign != null
            && campaign.snapshot().complete() && campaign.snapshot().participantId().equals(player.getUUID())
            && returnPoint != null && returnPoint.owner().equals(player.getUUID());
        PracticeRecovery.restore(trusted, () -> {
            restorePlayer(server, player, finishReturn);
            if (!finishReturn) {
                server.saveEverything(true,true,true);
                OwnerReturnVerifier.verify(server.getWorldPath(LevelResource.ROOT).normalize(),returnPoint,false);
            }
        }, () -> CampPlayerLease.restore(player));
    }

    private int startPractice(MinecraftServer server, ServerPlayer player) {
        if (server.isDedicatedServer()) { message(player, "Практика навыков доступна только в одиночном одноразовом мире."); return 0; }
        if (session != null || pendingJoins.containsKey(player.getUUID()) || server.getPlayerList().getPlayerCount() != 1) {
            message(player, "Сначала завершите активную сцену или восстановление после входа."); return 0;
        }
        if (!load(server, player)) return 0;
        if (campaign == null || !campaign.snapshot().complete() || !campaign.snapshot().participantId().equals(player.getUUID())) {
            message(player, "Отдельная практика доступна владельцу только после завершения первого эпизода."); return 0;
        }
        if (!server.getWorldData().getLevelName().toLowerCase(Locale.ROOT).startsWith("bekhilitt-test")) {
            message(player, "Для практики нужен отдельный мир Bekhilitt-Test*. Мир не изменён."); return 0;
        }
        try {
            if (EpicFightTrainingLoadout.hasPracticeLease(player)) restorePracticeParticipant(server, player, true);
            else {
                EpicFightTrainingLoadout.restore(player);
                if (returnPoint.pending()) restorePlayer(server, player, true);
            }
            EpicFightTrainingLoadout.verifyPracticeReady(player);
            if (levelDifficultyIsPeaceful(server)) throw new IllegalStateException("Для временных моделей сцены нужна сложность Easy, Normal или Hard.");
            SkillPracticeAttempt practice = new SkillPracticeAttempt(campaign.snapshot(), player.getUUID(), UUID.randomUUID());
            returnPoint = capture(player);
            persist(); // The completed campaign snapshot is not advanced or replaced.
            ServerLevel level = server.overworld();
            session = new Session(player.getUUID(), level, new EpisodeCamp(level, campaign.snapshot().campaignId()));
            session.practice = practice;
            session.stage = Stage.END; // Inert sentinel; practice has its own tick path.
            sendInactive(player);
            session.camp.beginLoading();
            message(player, "Загрузка отдельной практики навыков. Сюжет уже завершён и не меняется.");
            return 1;
        } catch (IOException | RuntimeException error) {
            message(player, "Практика не запущена: " + error.getMessage());
            if (session != null) stop(server, player, Interruption.PLATFORM_FAILURE, false);
            return 0;
        }
    }
    private void enterPractice(ServerPlayer player) {
        Session s = session;
        s.camp.stopCombat();
        move(player, s.level, s.camp.duelPlayer(), 180, -70);
        EpicFightTrainingLoadout.equipPractice(player, s.practice.attemptId());
        Network.send(player, new ArenaStatePayload(0));
        sendPresentation(player);
        player.sendSystemMessage(Component.literal("Bekhilitt: Demolition Leap — удержать ")
            .append(Component.keybind("key.epicfight.mover_skill"))
            .append(" и отпустить. Смотрите вверх для высоты. Meteor Slam — в воздухе смотреть вниз на землю дальше 6 блоков и коротко нажать ")
            .append(Component.keybind("key.epicfight.attack"))
            .append(". Практика без урона и разрушения блоков, до 5 минут. /bekhilitt stop — вернуть исходное состояние."));
    }
    private void tickPractice(ServerPlayer player) {
        if (!practiceSafe(player)) {
            message(player, "Практика остановлена: изменилось снаряжение, границы или защитная настройка мира.");
            stop(player.getServer(), player, Interruption.CANCELLED, false); return;
        }
        session.practice.tick();
        if (!session.practice.active()) {
            message(player, "Пять минут практики завершены; исходное состояние возвращается.");
            stop(player.getServer(), player, Interruption.CANCELLED, false);
        }
    }

    private EpisodeStore.ReturnPoint capture(ServerPlayer p) {
        return new EpisodeStore.ReturnPoint(p.getUUID(), p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ(),
            p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer().getName(), true);
    }
    private void persist() throws IOException { store.write(new EpisodeStore.Saved(campaign.snapshot(), returnPoint)); }
    public void joined(MinecraftServer server, ServerPlayer player) {
        // A borrowed practice state belongs to player NBT, not to a readable
        // campaign file or an enabled dev flag. Recovery may not depend on either.
        boolean practiceRecovery = EpicFightTrainingLoadout.hasPracticeLease(player) || CampPlayerLease.exists(player);
        if (practiceRecovery) pendingJoins.put(player.getUUID(), new EpisodeLoadGate());
        if ((!testProfileEnabled(server) && !practiceRecovery) || !load(server, player) || campaign == null) return;
        if (!campaign.snapshot().participantId().equals(player.getUUID())) return;
        // Join callbacks from different mods need not initialize player capabilities
        // in the same order. Restore at a bounded server-tick readiness boundary.
        pendingJoins.put(player.getUUID(), new EpisodeLoadGate());
    }
    private void restorePendingJoins(MinecraftServer server) {
        var iterator = pendingJoins.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { iterator.remove(); continue; }
            EpisodeLoadGate.State ready = entry.getValue().poll(EpicFightCapabilities.getServerPlayerPatch(player) != null);
            if (ready == EpisodeLoadGate.State.WAITING) continue;
            iterator.remove();
            if (ready != EpisodeLoadGate.State.READY) {
                message(player, "Epic Fight не подготовил состояние игрока; запись возврата сохранена. Повторите вход после проверки зависимостей.");
                continue;
            }
            try {
                // A training lease can remain even when the position lease has
                // already completed; restore it on every campaign-owner login.
                boolean ownedCampaign = campaign != null && campaign.snapshot().participantId().equals(player.getUUID());
                if (CampPlayerLease.exists(player)) restoreCampParticipant(server, player, true);
                else if (EpicFightTrainingLoadout.hasPracticeLease(player)) restorePracticeParticipant(server, player, true);
                else {
                    EpicFightTrainingLoadout.restore(player);
                    if (ownedCampaign && returnPoint != null && returnPoint.pending()) restorePlayer(server, player, true);
                    else server.saveEverything(true, true, true);
                }
                sendInactive(player);
                if (ownedCampaign) message(player, campaign.snapshot().complete() ? "Первый эпизод завершён; /bekhilitt camp — вернуться в свободный лагерь, /bekhilitt practice — практика."
                    : "Эпизод приостановлен на точке «" + campaign.snapshot().checkpointNodeId() + "». /bekhilitt resume — продолжить.");
            } catch (IOException | RuntimeException error) {
                message(player, "Не удалось восстановить исходное состояние; запись возврата сохранена: " + error.getMessage());
            }
        }
    }
    public void advance(ServerPlayer player, EpisodeAdvancePayload intent) {
        if (!isParticipant(player.getUUID()) || session.loading.state() != EpisodeLoadGate.State.READY) return;
        switch (EpisodeAdvanceRouting.route(presentationRevision(), intent.revision(), intent.action(),
                session.visit != null, session.stage, session.practice != null)) {
            case REFRESH -> { sendPresentation(player); return; }
            case STOP -> {
                stop(player.level().getServer(), player, Interruption.DIALOGUE_CLOSED, false); return;
            }
            case CLOSE_CAMP_TALK -> { closeCampTalk(player); return; }
            case ENTER_CAMP -> {
                try { enterCamp(player); } catch (IOException | RuntimeException error) { failure(player,error); }
                return;
            }
            case DIALOGUE -> { /* Keep proximity and page validation below. */ }
        }
        if (!session.stage.dialogue() || session.page < 0 || !nearSpeaker(player)) { sendPresentation(player); return; }
        EpisodeContent.Phase phase = content.phase(session.stage);
        if (session.page + 1 < phase.pages().size()) {
            session.page++; sendPresentation(player);
        } else advanceStage(player);
    }
    private long presentationRevision() { return networkRevision; }
    private void enterStage(ServerPlayer player) {
        Session s = session;
        s.stage = campaign.snapshot().stage(); s.ticks = 0; s.page = -1;
        switch (s.stage) {
            case INJURY -> {
                s.camp.griffith().setPos(s.camp.duelEnemy().x, s.camp.duelEnemy().y, s.camp.duelEnemy().z);
                move(player, s.level, s.camp.duelPlayer(), 180, 0);
            }
            case TRANSPORT -> {
                s.camp.griffith().setPos(s.camp.griffithPosition().x, s.camp.griffithPosition().y, s.camp.griffithPosition().z);
                s.camp.stopCombat();
                move(player, s.level, new Vec3(-9.5, s.camp.floor() + 2, -1.5), 180, 8);
            }
            case RECOVERY -> {
                s.camp.casca().setPos(s.camp.recoveryCasca().x, s.camp.recoveryCasca().y, s.camp.recoveryCasca().z);
                s.camp.casca().setYRot(90);
                move(player, s.level, s.camp.recoveryView(), -90, 0);
            }
            case AWAKENING, CASCA_DIALOGUE -> {
                s.camp.casca().setPos(s.camp.cascaPosition().x, s.camp.cascaPosition().y, s.camp.cascaPosition().z);
                s.camp.casca().setYRot(135);
                // Keep the movement the player made after awakening; relocate only for wake/resume.
                if (s.stage == Stage.AWAKENING || player.level() != s.level || !s.camp.contains(player.position()))
                    move(player, s.level, s.camp.wake(), -45, 0);
            }
            case GRIFFITH_DIALOGUE -> {
                s.camp.griffith().setPos(s.camp.griffithPosition().x, s.camp.griffithPosition().y, s.camp.griffithPosition().z);
                // A resumed checkpoint starts at the original return point, not necessarily inside camp.
                if (player.level() != s.level || !s.camp.contains(player.position()))
                    move(player, s.level, s.camp.wake(), -45, 0);
            }
            case DUEL -> {
                s.duel = campaign.beginEpicDuel(s.owner, campaign.snapshot().attemptId(), campaign.snapshot().revision(), s.camp.griffith().getUUID());
                s.presentedPhase = s.duel.phase();
                s.camp.griffith().moveTo(s.camp.duelEnemy().x, s.camp.duelEnemy().y, s.camp.duelEnemy().z, 0, 0);
                move(player, s.level, s.camp.duelPlayer(), 180, 0);
                EpicFightTrainingLoadout.equip(player, s.duel.attemptId());
                s.camp.stopCombat(); // Three-second server-owned preparation before native AI starts.
                title(player, "ПРИГОТОВЬТЕСЬ", "Бой через 3 секунды", 60);
                player.sendSystemMessage(Component.literal("Bekhilitt: ").append(controls())
                    .append(". Настоящий бой Epic Fight: попадания, защита и уклонение учитываются по результатам боя. Гриффит пока показан временной моделью. Канонический финал будет объявлен заранее; смерти нет."));
            }
            case END -> {
                s.camp.stopCombat();
                EpicFightTrainingLoadout.restore(player);
                if (s.duel != null) message(player, "Итог учебного боя: попадания " + s.duel.landed()
                    + ", блоки " + s.duel.blocks() + ", уклонения " + s.duel.dodges()
                    + ", полученные удары " + s.duel.received() + ". Сюжетное поражение и вступление сохранены.");
            }
        }
        Network.send(player, new ArenaStatePayload(s.stage == Stage.DUEL ? 1 : 0));
        sendPresentation(player);
    }
    private void advanceStage(ServerPlayer player) {
        var before = campaign.snapshot();
        if (!campaign.advance(session.owner, before.attemptId(), before.revision(), before.stage()).changed()) return;
        try { persist(); enterStage(player); }
        catch (IOException | RuntimeException error) { failure(player, error); }
    }
    private boolean nearSpeaker(ServerPlayer player) {
        if (!session.stage.dialogue()) return false;
        var actor = session.stage == Stage.CASCA_DIALOGUE ? session.camp.casca() : session.camp.griffith();
        return !actor.isRemoved() && player.position().distanceTo(actor.position()) <= 4.0 && player.hasLineOfSight(actor);
    }
    private void openDialogue(ServerPlayer player) {
        if (session.page >= 0 || !nearSpeaker(player)) return;
        var actor = session.stage == Stage.CASCA_DIALOGUE ? session.camp.casca() : session.camp.griffith();
        Vec3 look = actor.getEyePosition().subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
        move(player, session.level, player.position(), yaw, pitch);
        actor.setYRot((float) Math.toDegrees(Math.atan2(look.x, -look.z)));
        session.page = 0; sendPresentation(player);
    }
    private void sendPresentation(ServerPlayer player) {
        Session s = session;
        if (s.visit != null) {
            var speaker = s.visit.speaker();
            String name = speaker == CampVisit.Speaker.CASCA ? "casca" : "griffith";
            Network.send(player,new EpisodeStatePayload(speaker == null ? "camp" : "camp_talk", "end", ++networkRevision,
                speaker == null ? -1 : 0, speaker == null ? "" : "character.bekhilitt."+name,
                speaker == null ? "" : "story.bekhilitt.camp."+name, "story.bekhilitt.camp.objective", false));
            return;
        }
        if (s.practice != null) {
            Network.send(player, new EpisodeStatePayload("practice", "practice", ++networkRevision, -1, "",
                "story.bekhilitt.practice.body", "story.bekhilitt.practice.objective", false));
            return;
        }
        EpisodeContent.Phase phase = content.epicPhase(s.stage, s.duel == null ? null : s.duel.phase());
        boolean locked = s.stage.controlsLocked() || s.stage == Stage.END
            || (s.duel != null && s.duel.controlsLocked());
        String body = s.page >= 0 ? phase.pages().get(s.page) : phase.body();
        Network.send(player, new EpisodeStatePayload(s.stage.nodeId(), s.stage.nodeId(), ++networkRevision,
            s.page, phase.speaker(), body, phase.objective(), locked));
    }
    private void sendInactive(ServerPlayer player) {
        long revision = ++networkRevision;
        Network.send(player, new ArenaStatePayload(0));
        Network.send(player, new EpisodeStatePayload("inactive", "inactive", revision, -1, "", "", "", false));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true));
        player.displayClientMessage(Component.empty(), true);
    }
    /** Before native entity animations can reach their terrain-fracture callback. */
    public void preTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TrainingCombatRules.quarantinePractice(EpicFightTrainingLoadout.hasPracticeLease(player),
                    isParticipant(player.getUUID()) && practiceSafe(player)))
                EpicFightTrainingLoadout.quarantinePracticeActions(player);
        }
    }
    public void tick(MinecraftServer server) {
        restorePendingJoins(server);
        if (session == null) return;
        Session s = session;
        ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
        if (p == null) { stop(server, null, Interruption.DISCONNECT, true); return; }
        if (!p.isAlive()) { stop(server, p, Interruption.PLAYER_DEATH, true); return; }
        if (server.getPlayerList().getPlayerCount() != 1) { stop(server, p, Interruption.REGION_UNAVAILABLE, false); return; }
        if (s.loading.state() == EpisodeLoadGate.State.WAITING) {
            try {
                EpisodeLoadGate.State state = s.loading.poll(s.camp.regionReady());
                if (state == EpisodeLoadGate.State.TIMED_OUT)
                    throw new IllegalStateException("Лагерь не успел загрузиться; контрольная точка сохранена.");
                if (state != EpisodeLoadGate.State.READY) return;
                s.camp.prepare();
                p.setGameMode(GameType.ADVENTURE);
                if (s.practice != null) { enterPractice(p); return; }
                if (s.visit != null) { enterCamp(p); return; }
                message(p, "Первый эпизод: временные модели и игровой пересказ. /bekhilitt stop — безопасный выход, /bekhilitt resume — продолжить с контрольной точки.");
                enterStage(p);
            } catch (IOException | RuntimeException error) { failure(p, error); }
            return;
        }
        if (!s.camp.present()) { stop(server, p, Interruption.NPC_UNAVAILABLE, false); return; }
        if (server.getPlayerList().getPlayerCount() != 1 || p.level() != s.level
                || !(s.practice != null ? s.camp.containsPractice(p.position()) : s.camp.contains(p.position()))) {
            stop(server, p, Interruption.REGION_UNAVAILABLE, false); return;
        }
        try {
            s.ticks++;
            if (s.ticks % 100 == 0) s.camp.cleanOrphans();
            if (s.visit != null) {
                s.visit.tick();
                if (s.visit.speaker() != null) {
                    Entity actor=s.visit.speaker()==CampVisit.Speaker.CASCA?s.camp.casca():s.camp.griffith();
                    if (p.distanceTo(actor)>4 || !p.hasLineOfSight(actor)) closeCampTalk(p);
                }
                return;
            }
            if (s.practice != null) { tickPractice(p); return; }
            if (s.stage == Stage.DUEL) { tickDuel(p); return; }
            if (s.stage.dialogue()) {
                if (s.page >= 0 && !nearSpeaker(p)) { s.page = -1; sendPresentation(p); }
                else if (s.page < 0) openDialogue(p);
                return;
            }
            if (s.stage == Stage.INJURY) {
                double z = Math.min(5.4, 2.5 + s.ticks * 0.045);
                s.camp.griffith().setPos(3.5, s.camp.floor() + 1, z);
                if (s.ticks == 65) {
                    var patch = EpicFightCapabilities.getServerPlayerPatch(p);
                    if (patch != null) patch.playAnimationSynchronized(Animations.BIPED_HIT_LONG, 0);
                }
                move(p, s.level, s.camp.duelPlayer(), 180, s.ticks > 80 ? 45 : 0);
                p.setDeltaMovement(Vec3.ZERO);
            } else if (s.stage == Stage.TRANSPORT) {
                double fraction = Math.min(1.0, (double) s.ticks / content.phase(Stage.TRANSPORT).durationTicks());
                Vec3 carried = new Vec3(-9.5 - 2 * fraction, s.camp.floor() + 2, -1.5 - 4 * fraction);
                move(p, s.level, carried, 180, 8); p.setDeltaMovement(Vec3.ZERO);
            } else if (s.stage == Stage.RECOVERY) {
                move(p, s.level, s.camp.recoveryView(), -90, 0); p.setDeltaMovement(Vec3.ZERO);
            } else if (s.stage == Stage.END) { p.setDeltaMovement(Vec3.ZERO); }
            if (s.ticks >= content.phase(s.stage).durationTicks()) {
                if (s.stage != Stage.END) advanceStage(p); // END stays readable until explicitly dismissed into camp.
            }
        } catch (RuntimeException error) { failure(p, error); }
    }
    private void tickDuel(ServerPlayer p) {
        Session s = session;
        EpicDuelAttempt duel = s.duel;
        var griffith = s.camp.griffith();
        if ((duel.fighting() || duel.phase() == EpicDuelAttempt.Phase.PREPARATION)
                && !EpicFightTrainingLoadout.validEquipment(p)) {
            message(p, "Снаряжение изменилось: тренировка безопасно приостановлена, чтобы не расходовать ваши вещи. Для продолжения освободите руку, вторую руку и слоты брони.");
            stop(p.getServer(), p, Interruption.CANCELLED, false);
            return;
        }
        if (s.level.getDifficulty() == Difficulty.PEACEFUL) {
            stop(p.getServer(), p, Interruption.REGION_UNAVAILABLE, false);
            return;
        }
        duel.tick();
        int feedback = duel.consumeFeedback();
        boolean changed = s.presentedPhase != duel.phase();
        if (duel.ticks() % 5 == 0 || feedback != 0 || changed) {
            ServerPlayerPatch playerPatch = EpicFightCapabilities.getServerPlayerPatch(p);
            LivingEntityPatch<?> enemyPatch = EpicFightCapabilities.getEntityPatch(griffith, LivingEntityPatch.class);
            int stance = 0;
            if (playerPatch != null) {
                if (playerPatch.getSkill(SkillSlots.DODGE).isActivated()) stance = 3;
                else if (playerPatch.getSkill(SkillSlots.GUARD).isActivated()) stance = 2;
                else if (playerPatch.getEntityState().attacking()) stance = 1;
            }
            boolean enemyAttacking = enemyPatch != null && enemyPatch.getEntityState().getLevel() == 1;
            Network.send(p, new CombatStatusPayload(duel.landed(), duel.blocks(), duel.dodges(), stance, enemyAttacking, feedback));
        }
        if (duel.phase() == EpicDuelAttempt.Phase.PREPARATION) {
            p.displayClientMessage(Component.literal("Бой через "
                + ((EpicDuelAttempt.PREPARATION_TICKS - duel.phaseTick() + 19) / 20) + "…"), true);
            return;
        }
        if (duel.fighting()) {
            if (changed) {
                s.camp.beginCombat(p);
                if (diagnosticProfile()) LOGGER.info("Bekhilitt combat diagnostic: begin attempt={} item={} mode={} targetInvulnerable={} targetHealth={}",
                    duel.attemptId(), net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()),
                    EpicFightCapabilities.getServerPlayerPatch(p).getPlayerMode(), griffith.isInvulnerable(), griffith.getHealth());
                sendPresentation(p);
                p.displayClientMessage(Component.literal("Бой начался. Удар · защита · уклонение"), true);
                s.presentedPhase = duel.phase();
            }
            // Target selection only. Pathfinding, spacing, combo choice, colliders,
            // animation, stamina, guard and dodge are all Epic Fight's runtime.
            griffith.setTarget(p);
            return;
        }
        s.camp.stopCombat();
        if (duel.phase() == EpicDuelAttempt.Phase.WARNING) {
            if (changed) {
                LOGGER.info("Bekhilitt native duel result: attempt={} reason={} landed={} blocks={} dodges={} received={} fightTicks={}",
                    duel.attemptId(), duel.finishReason(), duel.landed(), duel.blocks(), duel.dodges(), duel.received(), duel.fightTicks());
                Network.send(p, new ArenaStatePayload(2));
                sendPresentation(p);
                message(p, finishReason(duel) + " Через 3 секунды начнётся объявленный канонический финал. Смерти и потери вещей нет.");
                title(p, "ФИНАЛ ДУЭЛИ", "Постановка через 3 секунды", 60);
            }
            p.displayClientMessage(Component.literal("Постановка через " + ((EpicDuelAttempt.WARNING_TICKS - duel.phaseTick() + 19) / 20) + "…"), true);
        } else if (duel.phase() == EpicDuelAttempt.Phase.FINISH || duel.phase() == EpicDuelAttempt.Phase.RESULT) {
            if (changed) {
                sendPresentation(p);
                title(p, duel.phase() == EpicDuelAttempt.Phase.FINISH ? "ПЕРЕХВАТ" : "ПОРАЖЕНИЕ", "Канонический финал", 70);
                if (duel.phase() == EpicDuelAttempt.Phase.FINISH) {
                    move(p, s.level, s.camp.duelPlayer(), 180, 12);
                    griffith.moveTo(s.camp.duelPlayer().x, s.camp.duelPlayer().y, s.camp.duelPlayer().z - 2.2, 0, 0);
                    var playerPatch = EpicFightCapabilities.getServerPlayerPatch(p);
                    if (playerPatch != null) playerPatch.playAnimationSynchronized(Animations.BIPED_KNOCKDOWN, 0);
                }
            }
            p.setDeltaMovement(Vec3.ZERO);
        } else if (duel.phase() == EpicDuelAttempt.Phase.COMPLETE) {
            var snapshot = campaign.snapshot();
            if (campaign.completeEpicDuel(s.owner, duel.attemptId(), snapshot.revision(), duel).changed()) {
                try { persist(); enterStage(p); } catch (IOException | RuntimeException error) { failure(p, error); }
            }
        }
        s.presentedPhase = duel.phase();
    }

    private static String finishReason(EpicDuelAttempt duel) {
        return switch (duel.finishReason()) {
            case TRAINING_OBSERVED -> "Вы выполнили попадания, защиту и уклонение.";
            case PLAYER_EXHAUSTED -> "Гриффит истощил ваши силы; бой остановлен до смертельного удара.";
            case OPPONENT_EXHAUSTED -> "Вы нанесли достаточно урона. Учебная часть закончена; сюжетный исход предопределён.";
            case TIME_LIMIT -> "Учебное время истекло. Неисполненные приёмы не засчитаны.";
        };
    }

    public void respawned(MinecraftServer server, ServerPlayer player) {
        if (isParticipant(player.getUUID())) stop(server, player, Interruption.PLAYER_DEATH, true);
        joined(server, player);
    }
    public void disconnected(MinecraftServer server, ServerPlayer player) {
        pendingJoins.remove(player.getUUID());
        if (isParticipant(player.getUUID())) stop(server, player, Interruption.DISCONNECT, true);
    }
    public void stopping(MinecraftServer server) {
        if (session != null) stop(server, server.getPlayerList().getPlayer(session.owner), Interruption.SAVE_EXIT, false);
        session = null; campaign = null; store = null; content = null; returnPoint = null; blocked = null; networkRevision = 0;
        pendingJoins.clear();
    }
    private void stop(MinecraftServer server, ServerPlayer player, Interruption reason, boolean keepReturnPending) {
        if (session == null) return;
        Session old = session;
        try {
            if (old.practice == null) campaign.interrupt(old.owner, campaign.snapshot().attemptId(), reason);
            else old.practice.stop();
            persist();
        } catch (IOException | RuntimeException error) {
            if (player != null) message(player, "Контрольную точку не удалось записать: " + error.getMessage());
        }
        old.loading.cancel();
        try { old.camp.close(); }
        catch (RuntimeException error) {
            if (player != null) try { message(player, "Очистка сцены завершилась с ошибкой: " + error.getMessage()); }
            catch (RuntimeException ignored) { /* Cleanup notification cannot prevent return. */ }
        } finally { session = null; }
        if (player != null) {
            if (CampPlayerLease.exists(player)) {
                try {
                    if (!player.isAlive()) throw new IllegalStateException("Игрок ещё не готов к возврату.");
                    restoreCampParticipant(server, player, !keepReturnPending);
                } catch (IOException | RuntimeException error) {
                    message(player, "Возврат из лагеря не завершён; запись и защита сохранены: " + error.getMessage());
                }
            } else if (EpicFightTrainingLoadout.hasPracticeLease(player)) {
                try {
                    if (!player.isAlive()) throw new IllegalStateException("Игрок ещё не готов к безопасному возврату.");
                    restorePracticeParticipant(server, player, !keepReturnPending);
                } catch (IOException | RuntimeException error) {
                    message(player, "Возврат практики не завершён; защита и запись сохранены: " + error.getMessage());
                }
            } else {
                try { EpicFightTrainingLoadout.restore(player); }
                catch (RuntimeException error) { message(player, "Возврат временного снаряжения отложен до следующего входа: " + error.getMessage()); }
                try { if (player.isAlive()) restorePlayer(server, player, !keepReturnPending); }
                catch (IOException | RuntimeException error) { message(player, "Возврат сохранён как незавершённый: " + error.getMessage()); }
            }
            try { sendInactive(player); } catch (RuntimeException ignored) { /* A closed connection cannot prevent server-side restoration. */ }
            if (campaign.snapshot().complete()) message(player, "Сюжет сохранён. /bekhilitt camp — вернуться в лагерь; /bekhilitt practice — практика.");
            if (!campaign.snapshot().complete()) message(player, "Эпизод приостановлен. /bekhilitt resume продолжит с безопасной контрольной точки.");
        }
    }
    private void restorePracticeParticipant(MinecraftServer server, ServerPlayer player, boolean finishReturnLease) throws IOException {
        boolean trustedReturn = blocked == null && !server.isDedicatedServer() && campaign != null
            && campaign.snapshot().complete() && campaign.snapshot().participantId().equals(player.getUUID())
            && returnPoint != null && returnPoint.owner().equals(player.getUUID());
        PracticeRecovery.restore(trustedReturn, () -> {
            // Retain the existing NBT marker throughout teleport/save/verification.
            // A corrupt campaign or missing dimension can never expose the player
            // to ambient damage in the camp by clearing protection first.
            restorePlayer(server, player, finishReturnLease);
            if (!finishReturnLease) {
                server.saveEverything(true, true, true);
                OwnerReturnVerifier.verify(server.getWorldPath(LevelResource.ROOT).normalize(), returnPoint, false);
            }
        }, () -> {
            EpicFightTrainingLoadout.restore(player);
            server.saveEverything(true, true, true);
        });
    }
    private void restorePlayer(MinecraftServer server, ServerPlayer player, boolean finishLease) throws IOException {
        var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(returnPoint.dimension()));
        ServerLevel target = server.getLevel(dimension);
        if (target == null) throw new IOException("Исходное измерение недоступно; возврат не сброшен");
        player.setGameMode(GameType.byName(returnPoint.mode()));
        move(player, target, new Vec3(returnPoint.x(), returnPoint.y(), returnPoint.z()), returnPoint.yaw(), returnPoint.pitch());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        if (finishLease) {
            // Integrated owner login prefers level.dat over playerdata/<uuid>.dat. Both must be saved.
            // Vanilla save APIs can log and swallow IO failures, so verify the actual owner source.
            server.saveEverything(true, true, true);
            OwnerReturnVerifier.verify(server.getWorldPath(LevelResource.ROOT).normalize(), returnPoint, server.isDedicatedServer());
            EpisodeStore.ReturnPoint completedReturn = returnPoint.withPending(false);
            store.write(new EpisodeStore.Saved(campaign.snapshot(), completedReturn));
            returnPoint = completedReturn; // Keep the in-memory lease too if verification/write fails.
        }
    }
    private void failure(ServerPlayer player, Exception error) {
        message(player, "Сцена остановлена безопасно: " + error.getMessage());
        stop(player.level().getServer(), player, Interruption.PLATFORM_FAILURE, false);
    }
    private static Component controls() {
        return Component.keybind("key.epicfight.attack").append(" — короткое нажатие: удар · ")
            .append(Component.keybind("key.epicfight.guard")).append(" — защита · ")
            .append(Component.keybind("key.epicfight.dodge")).append(" — уклонение");
    }
    private static void move(ServerPlayer p, ServerLevel level, Vec3 pos, float yaw, float pitch) { p.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch); }
    private static void message(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal("Bekhilitt: " + text)); }
    private static void title(ServerPlayer p, String title, String subtitle, int duration) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, duration, 5));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
    }
}
