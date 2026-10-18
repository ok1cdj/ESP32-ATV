package com.ok1cdj.atvrx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun newerRelease() {
        assertTrue(UpdateCheck.isNewer("0.12-beta", "0.11-beta"))
        assertTrue(UpdateCheck.isNewer("1.0", "0.11-beta"))
        assertTrue(UpdateCheck.isNewer("0.11", "0.11-beta"))           // the release after its betas
        assertTrue(UpdateCheck.isNewer("0.11.1-beta", "0.11-beta"))
    }

    @Test fun notNewer() {
        assertFalse(UpdateCheck.isNewer("0.11-beta", "0.11-beta"))
        assertFalse(UpdateCheck.isNewer("0.10-beta", "0.11-beta"))
        assertFalse(UpdateCheck.isNewer("0.11-beta", "v0.11-beta-3-g9117638-ci"))   // a CI build counts as its tag
        assertFalse(UpdateCheck.isNewer("0.12-beta", "0.4-dev"))                    // a local build is not nagged
        assertFalse(UpdateCheck.isNewer("nightly", "0.11-beta"))
    }
}
