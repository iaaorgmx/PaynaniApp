package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** PaynaniApp #164 (Q264 = A): el Catch-All de Telegram junto a los de SMS y WhatsApp. */
public class CatchAllChannelsTest {

    private static final List<String> THREE = Arrays.asList("sms", "whatsapp", "telegram");
    private static final List<String> TWO = Arrays.asList("sms", "whatsapp");

    private static RosterSource.CatchAll reply(String channel, List<String> channels) {
        return new RosterSource.CatchAll(false, false, "", "off", "", "", "", channel,
                channel.isEmpty() ? "" : "rules/catch-all-" + channel + ".md", channels);
    }

    @Test
    public void normalizeKeepsTheThreeChannelsAndDefaultsToSms() {
        assertEquals("sms", CatchAllChannels.normalize("sms"));
        assertEquals("whatsapp", CatchAllChannels.normalize("whatsapp"));
        assertEquals("telegram", CatchAllChannels.normalize("telegram"));
        assertEquals("sms", CatchAllChannels.normalize(null));
        assertEquals("sms", CatchAllChannels.normalize("signal"));
    }

    @Test
    public void telegramNeedsAGatewayThatListsIt() {
        assertTrue(CatchAllChannels.supported("telegram", reply("telegram", THREE)));
        // Una pasarela sin Telegram ignora `channel`: lo que contesta es otro Catch-All.
        assertFalse(CatchAllChannels.supported("telegram", reply("sms", TWO)));
        assertFalse(CatchAllChannels.supported("telegram", reply("", Collections.emptyList())));
        // Aunque liste telegram, una respuesta de otro canal no sirve.
        assertFalse(CatchAllChannels.supported("telegram", reply("sms", THREE)));
    }

    @Test
    public void aTelegramSaveReplyFromAnotherChannelIsRejected() {
        // catchall.save regresa el estado guardado: si la pasarela cambió entre leer y guardar, o
        // contesta por otro canal, la pantalla no lo adopta ni dice «Guardado» (Ximena en #165).
        RosterSource.CatchAll savedSms = new RosterSource.CatchAll(true, true, "texto de SMS", "active",
                "2026-10-31T12:00:00Z", "phone d_1", "e1", "sms", "rules/catch-all-sms.md", THREE);
        assertFalse(CatchAllChannels.supported("telegram", savedSms));
        RosterSource.CatchAll oldGateway = new RosterSource.CatchAll(true, true, "texto", "active",
                "2026-10-31T12:00:00Z", "phone d_1", "e1");
        assertFalse(CatchAllChannels.supported("telegram", oldGateway));
        RosterSource.CatchAll savedTelegram = new RosterSource.CatchAll(true, true, "texto", "active",
                "2026-10-31T12:00:00Z", "phone d_1", "e2", "telegram", "rules/catch-all-telegram.md", THREE);
        assertTrue(CatchAllChannels.supported("telegram", savedTelegram));
    }

    @Test
    public void smsAndWhatsAppStillAcceptAnOldGateway() {
        assertTrue(CatchAllChannels.supported("sms", reply("", Collections.emptyList())));
        assertTrue(CatchAllChannels.supported("whatsapp", reply("", Collections.emptyList())));
        assertTrue(CatchAllChannels.supported("whatsapp", reply("whatsapp", THREE)));
    }

    @Test
    public void othersAreTheRestInOrder() {
        assertEquals(Arrays.asList("whatsapp", "telegram"), CatchAllChannels.others("sms", THREE));
        assertEquals(Arrays.asList("sms", "telegram"), CatchAllChannels.others("whatsapp", THREE));
        assertEquals(Arrays.asList("sms", "whatsapp"), CatchAllChannels.others("telegram", THREE));
        // Sin Telegram en la pasarela, no se menciona.
        assertEquals(Collections.singletonList("whatsapp"), CatchAllChannels.others("sms", TWO));
        assertEquals(Collections.emptyList(), CatchAllChannels.others("sms", Collections.emptyList()));
    }
}
