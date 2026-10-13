package dev.bekhilitt.platform;

import dev.bekhilitt.campaign.CampaignSnapshot.Stage;
import dev.bekhilitt.protocol.EpisodeAdvancePayload.Action;

/** Pure routing after participant/readiness checks; only an exact presentation revision may act. */
final class EpisodeAdvanceRouting {
    enum Route { REFRESH, STOP, CLOSE_CAMP_TALK, ENTER_CAMP, DIALOGUE }

    private EpisodeAdvanceRouting() { }

    static Route route(long currentRevision, long intentRevision, Action action,
                       boolean campVisit, Stage stage, boolean practice) {
        if (intentRevision != currentRevision) return Route.REFRESH;
        // A cancellation retried after DUEL -> END must terminate, not create another active snapshot.
        // Ordinary Escape on END/camp_talk already sends NEXT, so those dismissals still stay in camp.
        if (action == Action.CANCEL) return Route.STOP;
        if (campVisit) return Route.CLOSE_CAMP_TALK;
        if (stage == Stage.END && !practice) return Route.ENTER_CAMP;
        if (practice) return Route.REFRESH;
        return Route.DIALOGUE;
    }
}
