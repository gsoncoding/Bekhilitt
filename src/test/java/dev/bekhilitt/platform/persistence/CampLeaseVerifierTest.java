package dev.bekhilitt.platform.persistence;

import dev.bekhilitt.platform.CampPlayerLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CampLeaseVerifierTest {
    @TempDir Path world;
    private final UUID owner=UUID.randomUUID();
    private CompoundTag lease() {
        var t=new CompoundTag();t.putInt("format",1);t.putString("owner",owner.toString());t.putString("mode","VANILLA");
        t.putFloat("health",13);t.putInt("food",7);t.putFloat("saturation",2.5f);t.putFloat("exhaustion",1.2f);t.putFloat("stamina",4);return t;
    }
    private CompoundTag root(CompoundTag lease) {
        var root=new CompoundTag();var data=new CompoundTag();var p=new CompoundTag();var extra=new CompoundTag();
        p.putUUID("UUID",owner);if(lease!=null)extra.put(CampPlayerLease.KEY,lease.copy());p.put("NeoForgeData",extra);
        p.putFloat("Health",13);p.putInt("foodLevel",7);p.putFloat("foodSaturationLevel",2.5f);p.putFloat("foodExhaustionLevel",1.2f);
        data.put("Player",p);root.put("Data",data);return root;
    }
    private void write(CompoundTag t)throws IOException {NbtIo.writeCompressed(t,world.resolve("level.dat"));}
    @Test void savedVitalsAndLeaseMustBothMatchBeforeRelease()throws IOException {
        write(root(lease()));assertDoesNotThrow(()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),true));
        var changed=root(lease());changed.getCompound("Data").getCompound("Player").putFloat("Health",2);write(changed);
        assertThrows(IOException.class,()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),true));
        assertDoesNotThrow(()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),false));
    }
    @Test void missingFieldsFutureVersionUnknownFieldsAndForeignOwnerAreNotGuessed() {
        for(String key:lease().getAllKeys()) {
            var broken=lease();broken.remove(key);assertThrows(IllegalArgumentException.class,()->CampPlayerLease.validate(owner,broken));
        }
        var future=lease();future.putInt("format",2);assertThrows(IllegalArgumentException.class,()->CampPlayerLease.validate(owner,future));
        var unknown=lease();unknown.putInt("unknown",1);assertThrows(IllegalArgumentException.class,()->CampPlayerLease.validate(owner,unknown));
        assertThrows(IllegalArgumentException.class,()->CampPlayerLease.validate(UUID.randomUUID(),lease()));
    }
    @Test void nonfiniteNegativeAndWrongTypeVitalsFailClosed() {
        for(String field:new String[]{"health","stamina","saturation","exhaustion"}) {
            for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1}) {
                var broken=lease();broken.putFloat(field,value);assertThrows(IllegalArgumentException.class,()->CampPlayerLease.validate(owner,broken));
            }
            var broken=lease();broken.putString(field,"1");assertThrows(IllegalArgumentException.class,()->CampPlayerLease.validate(owner,broken));
        }
    }
    @Test void missingOrStaleLeaseCannotConfirmRestoration()throws IOException {
        write(root(null));assertThrows(IOException.class,()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),true));
        var stale=lease();stale.putInt("food",18);write(root(stale));
        assertThrows(IOException.class,()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),true));
    }
    @Test void missingActualVitalsCannotLookLikeRestoredZeroValues()throws IOException {
        for(String key:new String[]{"Health","foodLevel","foodSaturationLevel","foodExhaustionLevel"}) {
            var r=root(lease());r.getCompound("Data").getCompound("Player").remove(key);write(r);
            assertThrows(IOException.class,()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),true));
        }
    }
    @Test void corruptSaveIsNeverOverwrittenByVerification()throws IOException {
        Files.writeString(world.resolve("level.dat"),"corrupted");
        assertThrows(IOException.class,()->TrainingLeaseVerifier.verifyCamp(world,owner,lease(),true));
        assertEquals("corrupted",Files.readString(world.resolve("level.dat")));
    }
}
