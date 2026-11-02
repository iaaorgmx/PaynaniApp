package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Los id de SMS_GATEWAY.md §3 y §4 con vectores fijos. Los valores esperados se
 * calcularon en Python con hashlib.sha256 siguiendo la fórmula del documento, así
 * que esta prueba falla si la app y el protocolo dejan de coincidir.
 */
public class ProtocolTest {

    @Test
    public void smsIdUsesE164Key() {
        assertEquals("3874bcbb9120f02d6bab037dd45a7e66e5bd36b9c577a8ac19dbb4c3089752c4",
                Protocol.smsId("+15550001111", 1759381781000L, "Hola, ¿mesa para 4? ñ"));
    }

    @Test
    public void receiverAndInboxGiveTheSameId() {
        // REL-1: el receptor ve "5550001111" y la bandeja guarda "+15550001111".
        String fromReceiver = Protocol.senderKey("5550001111", Phone.toE164("5550001111", "US"));
        String fromInbox = Protocol.senderKey("+15550001111", Phone.toE164("+15550001111", "US"));
        assertEquals(Protocol.smsId(fromInbox, 1759381781000L, "x"),
                Protocol.smsId(fromReceiver, 1759381781000L, "x"));
    }

    @Test
    public void alphanumericSenderKeepsRaw() {
        String key = Protocol.senderKey("O'Shop", Phone.toE164("O'Shop", "MX"));
        assertEquals("O'Shop", key);
        assertEquals("d7413a33001fae1c2962185dabb0b1f7a4c8884a741a4413614dcafd476312f7",
                Protocol.smsId(key, 1759381781000L, "Tu pedido salió"));
    }

    @Test
    public void callIds() {
        assertEquals("ed41008a5f81ba2865527e154a04afeab3722cfaf58525e918dfe61ae9c9fd73",
                Protocol.callId("call.missed", "+525511112222", 1759382405000L));
        // Número oculto: raw "" y e164 null, la llave queda vacía.
        assertEquals("9a12a9d58b2b1e88d8fffa85d9608b30f625a41fdd642a8abd79386d208ea7c6",
                Protocol.callId("call.missed", Protocol.senderKey("", null), 1759382405000L));
    }

    // --- el mismo SMS por dos rutas (#46, #51, regla b) ---------------------

    @Test
    public void sameSmsA1SuffixWithoutSpaces() {
        // El «#2»: el receptor lo leyó con el sufijo y la bandeja sin él.
        String inbox = "Prueba #2 con PaynaniApp";
        String receiver = inbox + "*/=0QcMMQ";
        assertTrue(Protocol.sameSms(receiver, inbox));
        assertTrue(Protocol.sameSms(inbox, receiver));
    }

    @Test
    public void sameSmsA2bPrefixWithSpacesIsAnotherSms() {
        assertFalse(Protocol.sameSms("Sí", "Sí, mañana a las 5"));
        assertFalse(Protocol.sameSms("Sí, mañana a las 5", "Sí"));
        // Basta un espacio, aunque sea al final o no sea el espacio común.
        assertFalse(Protocol.sameSms("Hola", "Hola "));
        assertFalse(Protocol.sameSms("Hola", "Hola\nadiós"));
    }

    @Test
    public void sameSmsA2() {
        assertFalse(Protocol.sameSms("Mesa para 4 a las 8", "¿Tienen estacionamiento?"));
        assertTrue(Protocol.sameSms("Mesa para 4 a las 8", "Mesa para 4 a las 8"));
        assertTrue(Protocol.sameSms("", ""));
        // No es prefijo: el sufijo tiene que ir al final.
        assertFalse(Protocol.sameSms("con PaynaniApp", "*/=0QcMMQcon PaynaniApp"));
        assertFalse(Protocol.sameSms(null, "x"));
    }

    @Test
    public void statusIdPerOrderAndStatus() {
        assertEquals("18e88e5fc6794d9c7ac57d8026ba50f8150108d673d5134eb478f12b7b5202b4",
                Protocol.statusId("o_6b1e0000000000000000000000000000", "sent"));
    }
}
