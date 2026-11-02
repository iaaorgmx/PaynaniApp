package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/** Las respuestas de SMS_GATEWAY.md §11, con los ejemplos de la sección (org.json real, en el teléfono). */
@RunWith(AndroidJUnit4.class)
public class GatewayRosterParseTest {

    private static final String LIST = "{\"account\": null, \"etag\": \"9c1e\","
            + "\"columns\": [\"Name\", \"Email\", \"Type\", \"GitHub\", \"Phone\", \"Rules\"],"
            + "\"members\": ["
            + "{\"row\": 1, \"name\": \"Julián Flores\", \"email\": \"julian@dominio.com\", \"type\": \"Human\","
            + " \"github\": \"julianflores\", \"phones\": [\"+525511112222\"], \"owner\": true,"
            + " \"rules\": \"julian-flores-3f2a\", \"has_rules\": true, \"rules_enabled\": true},"
            + "{\"row\": 2, \"name\": \"Iris\", \"email\": \"iris@dominio.com\", \"type\": \"AI Agent\","
            + " \"github\": \"\", \"phones\": [], \"owner\": false,"
            + " \"rules\": null, \"has_rules\": false, \"rules_enabled\": false}],"
            + "\"default_rules\": true}";

    @Test
    public void rosterListIsRead() throws Exception {
        RosterSource.Roster r = GatewayRosterSource.roster(new JSONObject(LIST));
        assertEquals("9c1e", r.etag);
        assertTrue(r.hasColumn("Phone"));
        assertTrue(r.defaultRules);
        assertEquals(0, r.savedRow);
        assertEquals(2, r.members.size());
        RosterMember owner = r.members.get(0);
        assertEquals(1, owner.row);
        assertEquals("Julián Flores", owner.name);
        assertEquals(Arrays.asList("+525511112222"), owner.phones);
        assertTrue(owner.owner);
        assertTrue(owner.hasRules && owner.rulesEnabled);
        RosterMember iris = r.members.get(1);
        assertEquals(RosterMember.AI_AGENT, iris.type);
        assertTrue(iris.phones.isEmpty());
        assertFalse(iris.owner);
    }

    @Test
    public void aRosterWithoutPhoneColumnSaysSo() throws Exception {
        RosterSource.Roster r = GatewayRosterSource.roster(new JSONObject(
                "{\"etag\": \"e\", \"columns\": [\"Name\", \"Email\", \"Type\"], \"members\": [], \"default_rules\": false}"));
        assertFalse(r.hasColumn("Phone"));
        assertFalse(r.hasColumn("GitHub"));
    }

    @Test
    public void rosterSaveCarriesTheSavedRow() throws Exception {
        RosterSource.Roster r = GatewayRosterSource.roster(new JSONObject(
                "{\"row\": 7, \"etag\": \"41aa\", \"columns\": [], \"members\": []}"));
        assertEquals(7, r.savedRow);
        assertEquals("41aa", r.etag);
    }

    @Test
    public void anInvalidErrorKeepsFieldAndReason() throws Exception {
        RosterSource.Failure f = GatewayRosterSource.failure(new JSONObject(
                "{\"type\": \"error\", \"code\": \"invalid\", \"ref\": \"q_5d0c\", \"field\": \"email\","
                        + " \"reason\": \"email_format\", \"detail\": \"«ana@» no es un correo válido\"}"));
        assertEquals("invalid", f.code);
        assertEquals("email", f.field);
        assertEquals("email_format", f.reason);
    }

    /** catchall.get/save (paynani PRD #535 FR-0a). */
    @Test
    public void catchAllIsRead() throws Exception {
        RosterSource.CatchAll c = GatewayRosterSource.catchAll(new JSONObject(
                "{\"file\": \"rules/catch-all.md\", \"exists\": true, \"enabled\": true,"
                        + " \"text\": \"Avísale a Julián.\", \"status\": \"active\","
                        + " \"updated_at\": \"2026-10-25T23:00:00Z\", \"updated_by\": \"PaynaniWeb 203.0.113.9\","
                        + " \"etag\": \"abc\"}"));
        assertTrue(c.exists);
        assertTrue(c.enabled);
        assertEquals("Avísale a Julián.", c.text);
        assertEquals("active", c.status);
        assertEquals("PaynaniWeb 203.0.113.9", c.updatedBy);
        assertEquals("abc", c.etag);
        RosterSource.CatchAll none = GatewayRosterSource.catchAll(new JSONObject(
                "{\"exists\": false, \"enabled\": false, \"text\": \"\", \"status\": \"off\", \"etag\": \"\"}"));
        assertFalse(none.exists);
        assertEquals("off", none.status);
        assertEquals("", none.etag);
        // Una pasarela vieja no manda channels: un solo Catch-All para SMS y WhatsApp (PRD #576 FR-11).
        assertFalse(c.perChannel());
        assertTrue(c.legacyFile());
    }

    /** catchall.get con un Catch-All por canal (paynani PRD #576 FR-11, FR-12). */
    @Test
    public void catchAllPerChannelIsRead() throws Exception {
        RosterSource.CatchAll c = GatewayRosterSource.catchAll(new JSONObject(
                "{\"channel\": \"whatsapp\", \"channels\": [\"sms\", \"whatsapp\"],"
                        + " \"file\": \"rules/catch-all-whatsapp.md\", \"exists\": true, \"enabled\": false,"
                        + " \"text\": \"\", \"status\": \"off\", \"etag\": \"e1\"}"));
        assertTrue(c.perChannel());
        assertEquals("whatsapp", c.channel);
        assertEquals(java.util.Arrays.asList("sms", "whatsapp"), c.channels);
        assertFalse(c.legacyFile());
        RosterSource.CatchAll migrating = GatewayRosterSource.catchAll(new JSONObject(
                "{\"channel\": \"sms\", \"channels\": [\"sms\", \"whatsapp\"],"
                        + " \"file\": \"rules/catch-all.md\", \"exists\": true, \"enabled\": true,"
                        + " \"text\": \"x\", \"status\": \"active\", \"etag\": \"e2\"}"));
        assertTrue(migrating.perChannel());
        assertTrue(migrating.legacyFile());
    }
}
