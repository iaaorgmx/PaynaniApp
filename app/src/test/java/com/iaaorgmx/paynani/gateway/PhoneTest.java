package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** La tabla de SMS_GATEWAY.md y los bordes de scripts/test_phone.py en paynani. */
public class PhoneTest {

    private static final String[][] TABLE = {
        {"55 1111 2222", "MX", "+525511112222"},
        {"+52 1 55 1111 2222", "MX", "+525511112222"},
        {"0052 55 1111-2222", "MX", "+525511112222"},
        {"(555) 000-1111", "US", "+15550001111"},
        {"1 555 000 1111", "US", "+15550001111"},
        {"+15550001111", "MX", "+15550001111"},
        {"26262", "MX", null},
        {"AMAZON", "MX", null},
    };

    @Test public void tablaDelDocumento() {
        for (String[] row : TABLE) {
            assertEquals(row[0] + " " + row[1], row[2], Phone.toE164(row[0], row[1]));
        }
    }

    @Test public void bordes() {
        assertNull(Phone.toE164("O'Shop", "MX"));
        assertNull(Phone.toE164("", "MX"));
        assertNull(Phone.toE164(null, "MX"));
        assertNull(Phone.toE164("911", "MX"));
        assertEquals("+525511112222", Phone.toE164("+5215511112222", "MX"));
        assertEquals("+525511112222", Phone.toE164("00521 55 1111 2222", "MX"));
        assertEquals("+525511112222", Phone.toE164("525511112222", "MX"));
        assertNull(Phone.toE164("5511112222", "ES"));
        assertNull(Phone.toE164("+52AB", "MX"));
        assertNull(Phone.toE164("+1234567890123456", "MX"));
        assertEquals("+525511112222", Phone.toE164("55 1111 2222", "MX"));
        assertEquals("+525511112222", Phone.toE164("55.1111.2222", "MX"));
        assertEquals("+15550001111", Phone.toE164("5550001111", "us"));
        assertEquals("+14165550000", Phone.toE164("416 555 0000", "CA"));
        assertEquals("+525511112222", Phone.toE164(Phone.toE164("55 1111 2222", "MX"), "MX"));
        assertNull(Phone.toE164("+٥٢٥٥١١١١٢٢٢٢", "MX"));
        assertNull(Phone.toE164("+５２５５１１１１２２２２", "MX"));
        assertNull(Phone.toE164("٥٥١١١١٢٢٢٢", "MX"));
        assertNull(Phone.toE164("+0525511112222", "MX"));
        assertNull(Phone.toE164("000525511112222", "MX"));
        assertEquals("+525511112222", Phone.toE164("5511112222", null));
    }
}
