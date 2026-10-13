package dev.bekhilitt.campaign;

import org.junit.jupiter.api.Test;

/** Each case also runs without JUnit in CampaignSelfTest.main. */
class CampaignTest {
    @Test void canonicalEpisode() { CampaignSelfTest.canonicalEpisode(); }
    @Test void mandatoryDuel() { CampaignSelfTest.mandatoryDuel(); }
    @Test void terminalCompletion() { CampaignSelfTest.terminalCompletion(); }
    @Test void staleRequests() { CampaignSelfTest.staleRequests(); }
    @Test void ownership() { CampaignSelfTest.ownership(); }
    @Test void interruptionMatrix() { CampaignSelfTest.interruptionMatrix(); }
    @Test void interruptedDuel() { CampaignSelfTest.interruptedDuel(); }
    @Test void foreignDuel() { CampaignSelfTest.foreignDuel(); }
    @Test void codecRoundTrips() { CampaignSelfTest.codecRoundTrips(); }
    @Test void malformedCodec() { CampaignSelfTest.malformedCodec(); }
    @Test void unsupportedVersions() { CampaignSelfTest.unsupportedVersions(); }
    @Test void corruptLedger() { CampaignSelfTest.corruptLedger(); }
    @Test void immutability() { CampaignSelfTest.immutability(); }
    @Test void stableEffectKeys() { CampaignSelfTest.stableEffectKeys(); }
    @Test void crashBeforeCommit() { CampaignSelfTest.crashBeforeCommit(); }
    @Test void crashAfterCommit() { CampaignSelfTest.crashAfterCommit(); }
    @Test void idempotentRecovery() { CampaignSelfTest.idempotentRecovery(); }
    @Test void revisionOverflow() { CampaignSelfTest.revisionOverflow(); }
    @Test void deterministicCodec() { CampaignSelfTest.deterministicCodec(); }
    @Test void identifiers() { CampaignSelfTest.identifiers(); }
    @Test void controlLease() { CampaignSelfTest.controlLease(); }
    @Test void adversarialEvents() { CampaignSelfTest.adversarialEvents(); }
}
