package team.rainfall.finality.loader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemVerTest {
    @Test
    void comparesPrereleaseAndBuildVersionsAccordingToSemVer() throws Exception {
        assertTrue(SemVer.parse("1.3.0-alpha.2").compareTo(SemVer.parse("1.3.0-alpha.10")) < 0);
        assertTrue(SemVer.parse("1.3.0-rc.1").compareTo(SemVer.parse("1.3.0")) < 0);
        assertEquals(0, SemVer.parse("1.3.0+build.1").compareTo(SemVer.parse("1.3.0+build.2")));
    }

    @Test
    void rejectsNonSemVerVersionsAndNumericPrereleaseLeadingZeroes() {
        assertThrows(SemVer.IOExceptionVersion.class, () -> SemVer.parse("1.3"));
        assertThrows(SemVer.IOExceptionVersion.class, () -> SemVer.parse("01.3.0"));
        assertThrows(SemVer.IOExceptionVersion.class, () -> SemVer.parse("1.3.0-01"));
    }

    @Test
    void matchesSingleAndCombinedVersionConstraints() throws Exception {
        VersionConstraint lowerBound = VersionConstraint.parse(">=1.3.0");
        assertTrue(lowerBound.matches(SemVer.parse("1.4.0")));
        assertFalse(lowerBound.matches(SemVer.parse("1.2.9")));

        VersionConstraint range = VersionConstraint.parse(">=1.3.0 <2.0.0");
        assertTrue(range.matches(SemVer.parse("1.9.9")));
        assertFalse(range.matches(SemVer.parse("2.0.0")));
    }
}
