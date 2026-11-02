package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.BatteryManager;

import org.junit.Test;

/** Las reglas de device.status (#109) sin Android. */
public class DeviceStatusTest {

    private static final long NOW = 1_792_343_122_000L;

    @Test
    public void firstReadingAlwaysGoes() {
        assertTrue(DeviceStatus.shouldSend(0, -1, false, 80, true, NOW));
    }

    @Test
    public void sameReadingWaitsTenMinutes() {
        long sent = NOW - DeviceStatus.REFRESH_MS + 60_000;
        assertFalse(DeviceStatus.shouldSend(sent, 23, false, 23, false, NOW));
        assertTrue(DeviceStatus.shouldSend(NOW - DeviceStatus.REFRESH_MS, 23, false, 23, false, NOW));
    }

    @Test
    public void aChangeGoesAtOnce() {
        long sent = NOW - 60_000;
        assertTrue(DeviceStatus.shouldSend(sent, 23, false, 22, false, NOW));
        assertTrue(DeviceStatus.shouldSend(sent, 23, false, 23, true, NOW));
    }

    @Test
    public void aClockThatWentBackDoesNotStopIt() {
        assertTrue(DeviceStatus.shouldSend(NOW + 3_600_000, 23, false, 23, false, NOW));
    }

    @Test
    public void percentIsAnIntegerFrom0To100() {
        assertEquals(23, DeviceStatus.pct(23, 100));
        assertEquals(50, DeviceStatus.pct(127, 255));
        assertEquals(100, DeviceStatus.pct(110, 100));
        assertEquals(-1, DeviceStatus.pct(-1, 100));
        assertEquals(-1, DeviceStatus.pct(50, 0));
    }

    @Test
    public void pluggedNamesFollowTheProtocol() {
        assertEquals("ac", DeviceStatus.plugged(BatteryManager.BATTERY_PLUGGED_AC));
        assertEquals("usb", DeviceStatus.plugged(BatteryManager.BATTERY_PLUGGED_USB));
        assertEquals("wireless", DeviceStatus.plugged(BatteryManager.BATTERY_PLUGGED_WIRELESS));
        assertEquals("none", DeviceStatus.plugged(0));
        assertEquals("none", DeviceStatus.plugged(8)); // dock: no lo nombra el protocolo
    }

    @Test
    public void fullCountsAsCharging() {
        assertTrue(DeviceStatus.charging(BatteryManager.BATTERY_STATUS_CHARGING));
        assertTrue(DeviceStatus.charging(BatteryManager.BATTERY_STATUS_FULL));
        assertFalse(DeviceStatus.charging(BatteryManager.BATTERY_STATUS_DISCHARGING));
        assertFalse(DeviceStatus.charging(BatteryManager.BATTERY_STATUS_NOT_CHARGING));
    }

    @Test
    public void temperatureComesInTenths() {
        assertEquals(31.5, DeviceStatus.tempC(315), 1e-9);
        assertTrue(Double.isNaN(DeviceStatus.tempC(Integer.MIN_VALUE)));
    }

    @Test
    public void idIs64HexAndChangesWithTheTime() {
        String id = DeviceStatus.id(NOW);
        assertTrue(id.matches("[0-9a-f]{64}"));
        assertEquals(id, DeviceStatus.id(NOW));
        assertNotEquals(id, DeviceStatus.id(NOW + 1));
    }
}
