package dev.bekhilitt.platform;

import dev.bekhilitt.combat.Duel;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Disposable dev arena only; no persistence, campaign flags, remote players or user saves. */
public final class PrototypeArena {
    private Session session;
    private static final Vec3 START = new Vec3(0.5, 101, 4.5);
    private static final Vec3 ENEMY = new Vec3(0.5, 101, 0.5);
    private static final class Session {
        final UUID owner;
        final ServerLevel level;
        final Vec3 returnPos;
        final float yaw, pitch;
        final GameType mode;
        final ArmorStand griffith;
        final Duel duel = new Duel();
        Session(ServerPlayer player, ArmorStand npc) {
            owner = player.getUUID(); level = player.level(); returnPos = player.position();
            yaw = player.getYRot(); pitch = player.getXRot(); mode = player.gameMode.getGameModeForPlayer(); griffith = npc;
        }
    }
    public boolean isParticipant(UUID id) { return session != null && session.owner.equals(id); }
    public int command(CommandSourceStack source, String action) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        MinecraftServer server = source.getServer();
        if (!Boolean.getBoolean("bekhilitt.dev") || server.isDedicatedServer()) {
            player.sendSystemMessage(Component.literal("Bekhilitt: только изолированный runClient с -Dbekhilitt.dev=true.")); return 0;
        }
        if (action.equals("start")) {
            if (session != null || server.getPlayerList().getPlayerCount() != 1) return 0;
            start(player); return 1;
        }
        if (!isParticipant(player.getUUID())) return 0;
        if (action.equals("stop")) { close(server); return 1; }
        Duel.Action intent = switch (action) {
            case "attack" -> Duel.Action.ATTACK; case "guard" -> Duel.Action.GUARD;
            default -> Duel.Action.DODGE;
        };
        if (!session.duel.act(intent)) return 0;
        if (intent == Duel.Action.ATTACK) player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (intent == Duel.Action.DODGE) {
            Vec3 v = player.getLookAngle().multiply(1, 0, 1).normalize().scale(-0.85);
            player.setDeltaMovement(v.x, 0.12, v.z); player.hurtMarked = true;
        }
        return 1;
    }
    private void start(ServerPlayer player) {
        ServerLevel level = player.level();
        // Deliberately fixed coordinates: this command is only for newly created disposable worlds.
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            level.setBlock(new BlockPos(x, 100, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            for (int y = 101; y <= 105; y++) level.setBlock(new BlockPos(x, y, z),
                (Math.abs(x) == 10 || Math.abs(z) == 10) && y <= 102 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
        // Recover a transient test NPC left by a forced process termination.
        for (var entity : level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(-11, 99, -11, 11, 107, 11)))
            if (entity.getTags().contains("bekhilitt.prototype")) entity.discard();
        ArmorStand npc = new ArmorStand(level, ENEMY.x, ENEMY.y, ENEMY.z);
        npc.setShowArms(true);
        npc.addTag("bekhilitt.prototype"); npc.setNoGravity(true); npc.setInvulnerable(true);
        npc.setCustomName(Component.literal("Гриффит • временная модель")); npc.setCustomNameVisible(true);
        npc.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        npc.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        npc.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        npc.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        level.addFreshEntity(npc);
        session = new Session(player, npc);
        sendState(player, 1);
        player.setGameMode(GameType.ADVENTURE);
        move(player, level, START, 180, 0);
        player.sendSystemMessage(Component.literal("Гатс — временная модель игрока. ").append(controls())
            .append(Component.literal(". F5: вид от третьего лица. /bekhilitt stop — выход. Исход дуэли — сюжетное поражение.")));
    }
    public void tick(MinecraftServer server) {
        if (session == null) return;
        Session s = session;
        ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
        if (p == null || !p.isAlive() || p.level() != s.level || s.griffith.isRemoved()
                || server.getPlayerList().getPlayerCount() != 1 || p.position().distanceTo(START) > 22) {
            s.duel.abort(); close(server); return;
        }
        Vec3 delta = s.griffith.position().subtract(p.position());
        boolean facing = p.getLookAngle().dot(delta.normalize()) > 0.35;
        boolean reach = delta.lengthSqr() <= 3.4 * 3.4 && p.hasLineOfSight(s.griffith);
        int before = s.duel.landed();
        Duel.Phase previous = s.duel.phase();
        s.duel.step(reach && facing, reach, facing);
        if (s.duel.phase() == Duel.Phase.FIGHT) {
            // Slow approach with a stationary, readable windup; always stays inside the flat arena.
            if (!s.duel.windup() && delta.length() > 2.5) {
                Vec3 next = s.griffith.position().add(delta.normalize().scale(-0.075));
                s.griffith.setPos(next.x, 101, next.z);
            }
            s.griffith.setYRot((float)(Math.toDegrees(Math.atan2(-delta.x, delta.z)) + 180));
            s.griffith.setRightArmPose(new net.minecraft.core.Rotations(s.duel.windup() ? -115 : -15, 0, 0));
            if (s.duel.landed() > before) p.sendSystemMessage(Component.literal("Удар достиг цели."));
            switch (s.duel.impact()) {
                case HIT -> { p.hurtMarked = true; p.setDeltaMovement(delta.normalize().scale(-0.35)); }
                case BLOCKED -> p.sendSystemMessage(Component.literal("Защита выдержала удар."));
                case EVADED -> p.sendSystemMessage(Component.literal("Уклонение: удар прошёл мимо."));
                default -> { }
            }
            p.displayClientMessage(Component.literal("ГАТС | Удары: " + s.duel.landed() + " | Пропущено: " + s.duel.pressure()
                + " | " + (s.duel.windup() ? "ГРИФФИТ: ЗАМАХ!" : s.duel.guarding() ? "ЗАЩИТА" : s.duel.dodging() ? "УКЛОНЕНИЕ" : "ГОТОВ")), true);
        } else if (s.duel.phase() == Duel.Phase.FINISH) {
            if (previous != Duel.Phase.FINISH) sendState(p, 2);
            // Technical staging, not a claim about the canonical finishing technique.
            move(p, s.level, START, 180, s.duel.finishTick() > 30 ? 55 : 0);
            p.setDeltaMovement(Vec3.ZERO);
            double z = Math.min(3.3, 0.5 + s.duel.finishTick() * 0.09);
            s.griffith.setPos(0.5, 101, z);
            s.griffith.setRightArmPose(new net.minecraft.core.Rotations(-90, 0, 0));
            p.displayClientMessage(Component.literal("Гриффит перехватывает инициативу… [техническая постановка]"), true);
        } else if (s.duel.phase() == Duel.Phase.COMPLETE) {
            p.sendSystemMessage(Component.literal("Канонический исход: поражение Гатса → вступление в Отряд Сокола. Конец боевого стенда; прогресс кампании не записывается."));
            close(server);
        }
    }
    private static Component controls() {
        return Component.keybind("key.bekhilitt.attack").append(" удар · ")
            .append(Component.keybind("key.bekhilitt.guard")).append(" защита · ")
            .append(Component.keybind("key.bekhilitt.dodge")).append(" уклонение");
    }
    private static void sendState(ServerPlayer p, int state) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.bekhilitt.protocol.ArenaStatePayload(state));
    }
    private static void move(ServerPlayer player, ServerLevel level, Vec3 pos, float yaw, float pitch) {
        player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, true);
    }
    public void close(MinecraftServer server) {
        if (session == null) return;
        Session old = session; session = null;
        old.griffith.discard();
        ServerPlayer p = server.getPlayerList().getPlayer(old.owner);
        if (p != null) {
            sendState(p, 0);
            p.setGameMode(old.mode);
            if (p.isAlive()) move(p, old.level, old.returnPos, old.yaw, old.pitch);
            p.displayClientMessage(Component.empty(), true);
        }
    }
}
