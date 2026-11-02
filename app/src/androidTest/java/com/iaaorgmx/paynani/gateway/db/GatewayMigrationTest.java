package com.iaaorgmx.paynani.gateway.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Las migraciones de paynani_gateway.db (#105): la base que dejó el
 * SQLiteOpenHelper de GatewayQueue llega a Room con todas sus filas.
 */
@RunWith(AndroidJUnit4.class)
public class GatewayMigrationTest {

    private static final String DB = "gateway-migration-test";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GatewayDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Lo que creaba GatewayQueue.onCreate en la versión 3 (0.6.3 a 0.6.6). */
    private static SQLiteDatabase createV3() {
        context().deleteDatabase(DB);
        SQLiteDatabase db = context().openOrCreateDatabase(DB, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE outq (id TEXT PRIMARY KEY, type TEXT NOT NULL, json TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, last_sent_at INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE orders (id TEXT PRIMARY KEY, status TEXT NOT NULL, at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS seen_in (key TEXT NOT NULL, sent_ms INTEGER NOT NULL, "
                + "text TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS seen_in_key_sent ON seen_in (key, sent_ms)");
        db.execSQL("CREATE TABLE IF NOT EXISTS unseen_pending (nkey TEXT NOT NULL, when_ms INTEGER NOT NULL, "
                + "posted_ms INTEGER NOT NULL, title TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (nkey, when_ms))");
        return db;
    }

    private static void fill(SQLiteDatabase db) {
        // Uno enviado sin ack, uno nunca enviado y un sms.status.
        db.execSQL("INSERT INTO outq VALUES ('s_1','sms.in','{\"type\":\"sms.in\",\"id\":\"s_1\"}',1000,5000,2)");
        db.execSQL("INSERT INTO outq (id,type,json,created_at) VALUES ('c_2','call.missed','{\"id\":\"c_2\"}',2000)");
        db.execSQL("INSERT INTO outq VALUES ('st_3','sms.status','{\"id\":\"st_3\"}',3000,0,0)");
        db.execSQL("INSERT INTO orders VALUES ('o_a','sent',4000)");
        db.execSQL("INSERT INTO orders VALUES ('o_b','accepted',4500)");
        db.execSQL("INSERT INTO seen_in VALUES ('+525550001111',1700000000000,'hola','s_1',6000)");
        db.execSQL("INSERT INTO seen_in VALUES ('+525550001111',1700000000000,'hola*/=GV939w','s_x',6001)");
        db.execSQL("INSERT INTO unseen_pending VALUES ('0|com.google.android.apps.messaging|2|k:17',111,222,'Ana',0)");
        db.execSQL("INSERT INTO unseen_pending (nkey,when_ms,posted_ms,title) VALUES ('k:19',333,444,'Beto')");
    }

    private static long count(SupportSQLiteDatabase db, String table) {
        try (Cursor c = db.query("SELECT count(*) FROM " + table)) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    private static void assertKept(SupportSQLiteDatabase db) {
        assertEquals(3, count(db, "outq"));
        assertEquals(2, count(db, "orders"));
        assertEquals(2, count(db, "seen_in"));
        assertEquals(2, count(db, "unseen_pending"));
        try (Cursor c = db.query("SELECT id,type,json,created_at,last_sent_at,attempts FROM outq ORDER BY created_at")) {
            c.moveToFirst();
            assertEquals("s_1", c.getString(0));
            assertEquals("sms.in", c.getString(1));
            assertEquals("{\"type\":\"sms.in\",\"id\":\"s_1\"}", c.getString(2));
            assertEquals(1000, c.getLong(3));
            assertEquals(5000, c.getLong(4));
            assertEquals(2, c.getInt(5));
            c.moveToNext();
            assertEquals("c_2", c.getString(0));
            assertEquals(0, c.getLong(4));
            assertEquals(0, c.getInt(5));
        }
        try (Cursor c = db.query("SELECT status, at FROM orders WHERE id = 'o_a'")) {
            c.moveToFirst();
            assertEquals("sent", c.getString(0));
            assertEquals(4000, c.getLong(1));
        }
        try (Cursor c = db.query("SELECT text, id, at FROM seen_in ORDER BY at")) {
            c.moveToFirst();
            assertEquals("hola", c.getString(0));
            assertEquals("s_1", c.getString(1));
            c.moveToNext();
            assertEquals("hola*/=GV939w", c.getString(0));
        }
        try (Cursor c = db.query("SELECT title, done FROM unseen_pending WHERE nkey = 'k:19' AND when_ms = 333")) {
            c.moveToFirst();
            assertEquals("Beto", c.getString(0));
            assertEquals(0, c.getInt(1));
        }
    }

    @Test
    public void keepsEveryRowFromVersion3() throws Exception {
        SQLiteDatabase db = createV3();
        fill(db);
        db.setVersion(3);
        db.close();
        assertKept(helper.runMigrationsAndValidate(DB, 4, true, GatewayDatabase.MIGRATION_3_4));
    }

    @Test
    public void emptyVersion3Migrates() throws Exception {
        SQLiteDatabase db = createV3();
        db.setVersion(3);
        db.close();
        SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(DB, 4, true, GatewayDatabase.MIGRATION_3_4);
        assertEquals(0, count(migrated, "outq"));
    }

    @Test
    public void version1GetsItsMissingTables() throws Exception {
        // La versión 1 sólo tenía outq y orders.
        context().deleteDatabase(DB);
        SQLiteDatabase db = context().openOrCreateDatabase(DB, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE outq (id TEXT PRIMARY KEY, type TEXT NOT NULL, json TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, last_sent_at INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE orders (id TEXT PRIMARY KEY, status TEXT NOT NULL, at INTEGER NOT NULL)");
        db.execSQL("INSERT INTO outq (id,type,json,created_at) VALUES ('c_2','call.missed','{}',2000)");
        db.setVersion(1);
        db.close();
        SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(DB, 4, true,
                GatewayDatabase.MIGRATION_1_2, GatewayDatabase.MIGRATION_2_3, GatewayDatabase.MIGRATION_3_4);
        assertEquals(1, count(migrated, "outq"));
        assertEquals(0, count(migrated, "seen_in"));
    }

    /** Las consultas del DAO sobre una base migrada se portan como las de antes. */
    @Test
    public void daoWorksOnTheMigratedFile() throws Exception {
        SQLiteDatabase v3 = createV3();
        fill(v3);
        v3.setVersion(3);
        v3.close();
        helper.runMigrationsAndValidate(DB, 4, true, GatewayDatabase.MIGRATION_3_4).close();

        GatewayDatabase room = Room.databaseBuilder(context(), GatewayDatabase.class, DB)
                .addMigrations(GatewayDatabase.MIGRATION_3_4, GatewayDatabase.MIGRATION_4_5,
                        GatewayDatabase.MIGRATION_5_6).build();
        GatewayDao dao = room.dao();

        // due: nunca enviado (c_2, st_3) y el enviado hace más de retry (s_1).
        List<OutItem> due = dao.due(6000, 50);
        assertEquals(3, due.size());
        assertEquals("s_1", due.get(0).id);
        // Con s_1 enviado hace poco (5000 >= 4000) sólo salen los nunca enviados.
        List<OutItem> fresh = dao.due(4000, 50);
        assertEquals(2, fresh.size());
        assertEquals("c_2", fresh.get(0).id);

        // Encolar el mismo id otra vez no lo duplica.
        OutItem again = new OutItem();
        again.id = "s_1";
        again.type = "sms.in";
        again.json = "{}";
        assertEquals(-1, dao.insertOut(again));

        // Una orden repetida se reconoce.
        assertEquals("sent", dao.orderStatus("o_a"));
        assertNull(dao.orderStatus("o_z"));

        // ack
        dao.removeOut("s_1");
        assertNull(dao.getOut("s_1"));
        assertNotNull(dao.getOut("c_2"));

        // seen_in por ventana y unseen_pending.
        assertTrue(dao.seenBetween(5999, 6001));
        assertFalse(dao.seenBetween(7000, 8000));
        assertEquals(2, dao.dueUnseen(1000).size());
        dao.doneUnseen("k:19", 333);
        assertEquals(1, dao.dueUnseen(1000).size());
        room.close();
    }

    /** 4 a 5 (PaynaniApp#111): agrega human_sends sin tocar lo demás. */
    @Test
    public void version4GetsHumanSends() throws Exception {
        context().deleteDatabase(DB);
        SupportSQLiteDatabase v4 = helper.createDatabase(DB, 4);
        v4.execSQL("INSERT INTO outq (id, type, json, created_at) VALUES ('s_1', 'sms.in', '{}', 1000)");
        v4.close();
        SupportSQLiteDatabase v5 = helper.runMigrationsAndValidate(DB, 5, true, GatewayDatabase.MIGRATION_4_5);
        assertEquals(1, count(v5, "outq"));
        assertEquals(0, count(v5, "human_sends"));
        v5.close();

        GatewayDatabase room = Room.databaseBuilder(context(), GatewayDatabase.class, DB)
                .addMigrations(GatewayDatabase.MIGRATION_4_5, GatewayDatabase.MIGRATION_5_6).build();
        GatewayDao dao = room.dao();
        HumanSend row = new HumanSend();
        row.local_id = "h_" + "a".repeat(32);
        row.to_raw = "55 1111 2222";
        row.to_e164 = "+525511112222";
        row.text = "Sí, mañana.";
        row.status = "sending";
        row.parts = 1;
        dao.insertHuman(row);
        dao.setHumanStatus(row.local_id, "sent", null, 2000);
        assertEquals("sent", dao.getHuman(row.local_id).status);
        assertEquals(1, dao.humanTo("nada", "+525511112222").size());
        room.close();
    }

    /**
     * 5 a 6 (paynani #489, #491): agrega order_marks sin tocar lo demás, y el tope
     * por hora cuenta sólo las órdenes del agente.
     */
    @Test
    public void version5GetsOrderMarks() throws Exception {
        context().deleteDatabase(DB);
        SupportSQLiteDatabase v5 = helper.createDatabase(DB, 5);
        v5.execSQL("INSERT INTO orders (id, status, at) VALUES ('o_" + "a".repeat(32) + "', 'sent', 5000)");
        v5.execSQL("INSERT INTO orders (id, status, at) VALUES ('w_" + "b".repeat(32) + "', 'sent', 5000)");
        v5.execSQL("INSERT INTO orders (id, status, at) VALUES ('a_" + "c".repeat(32) + "', 'delivered', 5000)");
        v5.close();
        SupportSQLiteDatabase v6 = helper.runMigrationsAndValidate(DB, 6, true, GatewayDatabase.MIGRATION_5_6);
        assertEquals(3, count(v6, "orders"));
        assertEquals(0, count(v6, "order_marks"));
        v6.close();

        GatewayDatabase room = Room.databaseBuilder(context(), GatewayDatabase.class, DB)
                .addMigrations(GatewayDatabase.MIGRATION_5_6).build();
        GatewayDao dao = room.dao();
        assertEquals(1, dao.sentSince(1000));
        OrderMark mark = new OrderMark();
        mark.order_id = "w_" + "b".repeat(32);
        mark.origin = "owner";
        mark.to_e164 = "+525511112222";
        mark.text = "Hola";
        mark.at = 5000;
        dao.insertMark(mark);
        dao.insertMark(mark);
        assertEquals(1, dao.marksTo("+525511112222").size());
        dao.forgetMarksBefore(6000);
        assertEquals(0, dao.marksTo("+525511112222").size());
        room.close();
    }
}
