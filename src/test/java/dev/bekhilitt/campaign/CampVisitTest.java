package dev.bekhilitt.campaign;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.bekhilitt.campaign.CampaignSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class CampVisitTest {
    private final UUID owner=UUID.randomUUID();
    private CampaignSnapshot complete() {
        return CampaignSnapshot.at(UUID.randomUUID(),owner,20,Stage.END,UUID.randomUUID(),UUID.randomUUID(),Status.COMPLETE,Interruption.NONE);
    }
    @Test void onlyCompletedOwnerMayVisit() {
        var fresh=CampaignService.create(UUID.randomUUID(),owner).snapshot();
        assertThrows(IllegalStateException.class,()->new CampVisit(fresh,owner));
        assertThrows(IllegalStateException.class,()->new CampVisit(complete(),UUID.randomUUID()));
        assertThrows(IllegalStateException.class,()->new CampVisit(null,owner));
        assertDoesNotThrow(()->new CampVisit(complete(),owner));
    }
    @Test void repeatedVisitsTalksAndRestoresCannotGrantEffectsOrReplayDuel() {
        var saved=complete();String encoded=CampaignCodec.encode(saved);
        for(int n=0;n<40;n++) {
            var campaign=CampaignService.restore(CampaignCodec.decode(encoded));
            var visit=new CampVisit(campaign.snapshot(),owner);
            assertTrue(visit.open(CampVisit.Speaker.CASCA,3,true));visit.close();
            for(int tick=0;tick<10;tick++)visit.tick();
            assertTrue(visit.open(CampVisit.Speaker.GRIFFITH,2,true));visit.close();
            assertEquals(CampaignService.Result.TERMINAL,campaign.resume(owner));
            assertEquals(CampaignService.Result.TERMINAL,campaign.interrupt(owner,saved.attemptId(),Interruption.SAVE_EXIT));
            assertEquals(encoded,CampaignCodec.encode(campaign.snapshot()));
            assertThrows(IllegalStateException.class,()->campaign.beginEpicDuel(owner,saved.attemptId(),saved.revision(),UUID.randomUUID()));
        }
    }
    @Test void proximityLineOfSightAndDoubleClickAreAuthoritative() {
        var visit=new CampVisit(complete(),owner);
        assertFalse(visit.open(CampVisit.Speaker.CASCA,5,true));
        assertFalse(visit.open(CampVisit.Speaker.CASCA,2,false));
        assertFalse(visit.open(CampVisit.Speaker.CASCA,Double.NaN,true));
        assertTrue(visit.open(CampVisit.Speaker.CASCA,2,true));
        assertFalse(visit.open(CampVisit.Speaker.GRIFFITH,2,true));
        visit.close();assertFalse(visit.open(CampVisit.Speaker.CASCA,2,true));
        for(int i=0;i<10;i++)visit.tick();
        assertTrue(visit.open(CampVisit.Speaker.GRIFFITH,2,true));
    }
}
