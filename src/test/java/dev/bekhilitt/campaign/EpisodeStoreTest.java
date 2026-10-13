package dev.bekhilitt.campaign;

import org.junit.jupiter.api.Test;

/** Same filesystem regressions as EpisodeStoreSelfTest.main. */
class EpisodeStoreTest {
    @Test void roundTrip() throws Exception { EpisodeStoreSelfTest.roundTrip(); }
    @Test void atomicFileRoundTrip() throws Exception { EpisodeStoreSelfTest.atomicFileRoundTrip(); }
    @Test void corruptFilePreserved() throws Exception { EpisodeStoreSelfTest.corruptFilePreserved(); }
    @Test void futureFilePreserved() throws Exception { EpisodeStoreSelfTest.futureFilePreserved(); }
    @Test void oversizedFilePreserved() throws Exception { EpisodeStoreSelfTest.oversizedFilePreserved(); }
    @Test void foreignOwnerRejected() throws Exception { EpisodeStoreSelfTest.foreignOwnerRejected(); }
    @Test void staleRevisionRejected() throws Exception { EpisodeStoreSelfTest.staleRevisionRejected(); }
    @Test void conflictingRevisionRejected() throws Exception { EpisodeStoreSelfTest.conflictingRevisionRejected(); }
    @Test void returnPendingChange() throws Exception { EpisodeStoreSelfTest.returnPendingChange(); }
    @Test void cachedRevisionRejected() throws Exception { EpisodeStoreSelfTest.cachedRevisionRejected(); }
    @Test void cachedCorruptionPreserved() throws Exception { EpisodeStoreSelfTest.cachedCorruptionPreserved(); }
    @Test void invalidReturnPoints() throws Exception { EpisodeStoreSelfTest.invalidReturnPoints(); }
    @Test void malformedEnvelope() throws Exception { EpisodeStoreSelfTest.malformedEnvelope(); }
}
