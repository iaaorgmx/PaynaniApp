package com.iaaorgmx.paynani.webchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** paynani PRD #653 FR-5: los archivos van por HTTP a la misma pasarela del WebSocket emparejado. */
public class GatewayWebchatSourceTest {

    @Test
    public void theHttpAddressIsTheSameGatewayAsTheWebSocket() {
        assertEquals("https://agente.ngrok.app/sms/webchat/upload",
                GatewayWebchatSource.httpUrl("wss://agente.ngrok.app/sms/ws", "/sms/webchat/upload").toString());
        assertEquals("http://127.0.0.1:8765/sms/webchat/file",
                GatewayWebchatSource.httpUrl("ws://127.0.0.1:8765/sms/ws", "/sms/webchat/file").toString());
        assertNull(GatewayWebchatSource.httpUrl("", "/sms/webchat/file"));
        assertNull(GatewayWebchatSource.httpUrl("ftp://x/sms/ws", "/sms/webchat/file"));
    }

    @Test
    public void theDraftComesAsTextOrAsAnObject() {
        assertEquals("Hola", GatewayWebchatSource.draftText("Hola"));
        assertNull(GatewayWebchatSource.draftText(""));
        assertNull(GatewayWebchatSource.draftText(null));
    }
}
