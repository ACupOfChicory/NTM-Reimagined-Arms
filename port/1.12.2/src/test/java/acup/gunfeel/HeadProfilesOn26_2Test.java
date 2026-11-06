package acup.gunfeel;

import org.junit.jupiter.api.Test;

// port: the shared HeadProfilesTest's case of NTM's OBJ mobs drawn without Flywheel (glyphids by the order of their
// parts, Mask Man and its skull, the cyber crab's numbered shell) on 26.2's markup with the port's code: the port's own
// markup (the cyber crab without a head, Mask Man by its 1.12.2 groups) is off for it; build.gradle.kts leaves the case
// out of the shared run, HeadsPortTest and HeadsCeTest hold the port's markup
class HeadProfilesOn26_2Test {
    @Test
    void objMobsOn26_2Markup() {
        HeadProfiles.portBank = false;
        try {
            new HeadProfilesTest().withoutFlywheelNtmObjMobsGoByTheOrderOfTheirVisualsParts();
        } finally {
            HeadProfiles.portBank = true;
        }
    }
}
