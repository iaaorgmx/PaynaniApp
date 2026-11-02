package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProtocolTest {

    @Test
    public void statusIdIsTheSameAsTheGateway() {
        // paynani: gateway.status_id("o_1", "rejected") = sha256("o_1\nrejected").
        assertEquals(Protocol.sha256Hex("o_1\nrejected"), Protocol.statusId("o_1", "rejected"));
        assertEquals("a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3", Protocol.sha256Hex("123"));
    }
}
