package com.iaaorgmx.paynani;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** #124: the Samsung guide follows the Android version, because the menus change with One UI. */
public class BackgroundScreensSamsungTest {

    @Test
    public void eachAndroidVersionGetsItsSteps() {
        assertEquals(R.string.bg_guide_steps_samsung_9, BackgroundScreens.samsungSteps(26));
        assertEquals(R.string.bg_guide_steps_samsung_9, BackgroundScreens.samsungSteps(28)); // Note 8, One UI 1
        assertEquals(R.string.bg_guide_steps_samsung_10, BackgroundScreens.samsungSteps(29)); // One UI 2
        assertEquals(R.string.bg_guide_steps_samsung, BackgroundScreens.samsungSteps(30)); // One UI 3+
        assertEquals(R.string.bg_guide_steps_samsung, BackgroundScreens.samsungSteps(35));
    }
}
