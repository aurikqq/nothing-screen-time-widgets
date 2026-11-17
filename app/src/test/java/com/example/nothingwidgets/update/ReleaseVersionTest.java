package com.example.nothingwidgets.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseVersionTest {
    @Test public void newerStableUsesNumericComponents() {
        assertTrue(ReleaseVersion.isNewerStable("v0.10", "0.9"));
        assertTrue(ReleaseVersion.isNewerStable("v1.0.1", "1.0"));
        assertTrue(ReleaseVersion.isNewerStable("v0.7", "0.6-dev"));
    }
    @Test public void equalAndOlderVersionsAreHidden() {
        assertFalse(ReleaseVersion.isNewerStable("v0.6", "0.6"));
        assertFalse(ReleaseVersion.isNewerStable("0.6.0", "v0.6"));
        assertFalse(ReleaseVersion.isNewerStable("v0.5.9", "0.6-dev"));
        assertFalse(ReleaseVersion.isNewerStable("0.9", "0.10"));
    }
    @Test public void stableReplacesSameVersionDevelopmentBuild() {
        assertTrue(ReleaseVersion.isNewerStable("v0.6", "0.6-dev"));
        assertTrue(ReleaseVersion.isNewerStable("1.0", "1.0-rc.1"));
        assertFalse(ReleaseVersion.isNewerStable("1.0", "1.0+build.9"));
    }
    @Test public void unknownAndPrereleaseTagsFailClosed() {
        assertFalse(ReleaseVersion.isNewerStable("v1.0-rc1", "0.6"));
        assertFalse(ReleaseVersion.isNewerStable("release-latest", "0.6"));
        assertFalse(ReleaseVersion.isNewerStable("1.0", "unknown"));
        assertFalse(ReleaseVersion.isNewerStable(null, "0.6"));
        assertFalse(ReleaseVersion.isNewerStable("1.0", null));
    }
    @Test public void largeComponentsCannotOverflow() {
        assertTrue(ReleaseVersion.isNewerStable("99999999999999999999999.0", "1.0"));
    }
}
