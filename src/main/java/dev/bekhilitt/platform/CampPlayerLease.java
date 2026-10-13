package dev.bekhilitt.platform;

import dev.bekhilitt.platform.persistence.TrainingLeaseVerifier;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Durable vitals for a non-combat visit. Never replaces inventory or teaches skills. */
public final class CampPlayerLease {
    public static final String KEY = "bekhilitt_camp_visit_v1";
    private CampPlayerLease() { }
    public static boolean exists(ServerPlayer p) { return p.getPersistentData().contains(KEY); }
    public static void validate(UUID owner, CompoundTag tag) {
        if (!tag.getAllKeys().equals(Set.of("format","owner","health","food","saturation","exhaustion","stamina","mode"))
                || !tag.contains("format",3) || tag.getInt("format") != 1
                || !tag.contains("owner",8) || !owner.toString().equals(tag.getString("owner")))
            throw new IllegalArgumentException("Повреждена запись возврата из лагеря; она сохранена.");
        if (!tag.contains("mode",8) || !Set.of("VANILLA","EPICFIGHT").contains(tag.getString("mode")))
            throw new IllegalArgumentException("Некорректный режим Epic Fight в записи лагеря");
        for (String field : new String[]{"health","saturation","exhaustion","stamina"})
            if (!tag.contains(field,5) || !Float.isFinite(tag.getFloat(field)) || tag.getFloat(field)<0)
                throw new IllegalArgumentException("Некорректное состояние лагеря: " + field);
        if (tag.getFloat("health")<=0 || !tag.contains("food",3) || tag.getInt("food")<0 || tag.getInt("food")>20)
            throw new IllegalArgumentException("Некорректное здоровье/голод в записи лагеря");
    }
    public static void capture(ServerPlayer p) throws IOException {
        if (exists(p)) throw new IllegalStateException("Сначала требуется восстановление предыдущего посещения лагеря.");
        var patch=EpicFightCapabilities.getServerPlayerPatch(p);
        if (patch==null) throw new IllegalStateException("Epic Fight ещё не готов.");
        CompoundTag tag=new CompoundTag(); tag.putInt("format",1); tag.putString("owner",p.getUUID().toString());
        tag.putFloat("health",p.getHealth()); tag.putInt("food",p.getFoodData().getFoodLevel());
        tag.putFloat("saturation",p.getFoodData().getSaturationLevel()); tag.putFloat("exhaustion",p.getFoodData().getExhaustionLevel());
        tag.putFloat("stamina",patch.getStamina()); tag.putString("mode",patch.getPlayerMode().name()); validate(p.getUUID(),tag);
        p.getPersistentData().put(KEY,tag);
        p.getServer().saveEverything(true,true,true);
        TrainingLeaseVerifier.verifyCamp(p.getServer().getWorldPath(LevelResource.ROOT),p.getUUID(),tag,false);
    }
    /** Caller first returns to the trusted original position while this protection stays present. */
    public static void restore(ServerPlayer p) throws IOException {
        if (!exists(p)) return;
        if (!p.getPersistentData().contains(KEY,10)) throw new IllegalStateException("Повреждён тип записи лагеря.");
        CompoundTag tag=p.getPersistentData().getCompound(KEY); validate(p.getUUID(),tag);
        var patch=EpicFightCapabilities.getServerPlayerPatch(p);
        if(patch==null) throw new IllegalStateException("Epic Fight ещё не готов к возврату.");
        p.setHealth(tag.getFloat("health")); p.getFoodData().setFoodLevel(tag.getInt("food"));
        p.getFoodData().setSaturation(tag.getFloat("saturation")); p.getFoodData().setExhaustion(tag.getFloat("exhaustion"));
        patch.toMode(yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch.PlayerMode.valueOf(tag.getString("mode")),true);
        patch.setStamina(tag.getFloat("stamina"));
        p.getServer().saveEverything(true,true,true);
        TrainingLeaseVerifier.verifyCamp(p.getServer().getWorldPath(LevelResource.ROOT),p.getUUID(),tag,true);
        p.getPersistentData().remove(KEY);
        p.getServer().saveEverything(true,true,true);
    }
}
