package com.iaaorgmx.paynani.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * La migración 5 → 6 (#105): la base que dejó EventLog_SQL, con cualquiera de
 * sus tres CREATE, llega a Room con todas sus filas.
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    private static final String DB = "migration-test";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    /** Una base versión 5 como la dejaba la app sin Room. */
    private void createV5(String createEventLog, boolean withSmsList) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DB);
        SQLiteDatabase db = context.openOrCreateDatabase(DB, Context.MODE_PRIVATE, null);
        if (withSmsList) {
            db.execSQL("CREATE TABLE sms_list (id INTEGER PRIMARY KEY AUTOINCREMENT, phone TEXT)");
        }
        if (createEventLog != null) {
            db.execSQL(createEventLog);
            insert(db, "incoming_sms", "hola", "5550001111", "2026-10-15 23:46:44");
            insert(db, "sent_server", "hola", "5550001111", "2026-10-15 23:46:45");
            insert(db, "gateway_revoked", "", "", "2026-10-16 00:01:00");
            insert(db, "call_not_answered", null, "5550002222", null);
        }
        db.setVersion(5);
        db.close();
    }

    private static void insert(SQLiteDatabase db, String evento, String msg, String phone, String ts) {
        ContentValues v = new ContentValues();
        v.put("evento", evento);
        v.put("msg", msg);
        v.put("phone", phone);
        v.put("timestamp", ts);
        db.insertOrThrow("event_log", null, v);
    }

    private SupportSQLiteDatabase migrate() throws Exception {
        return helper.runMigrationsAndValidate(DB, 6, true, AppDatabase.MIGRATION_5_6);
    }

    /**
     * Con sms_list. La migración no la borra (no se pierde nada) y Room no la
     * revisa al abrir; sólo el helper de pruebas la marcaría como sobrante.
     */
    private SupportSQLiteDatabase migrateKeepingSmsList() throws Exception {
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(DB, 6, false, AppDatabase.MIGRATION_5_6);
        Cursor c = db.query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='sms_list'");
        c.moveToFirst();
        assertEquals(1, c.getLong(0));
        c.close();
        return db;
    }

    private static void assertRowsKept(SupportSQLiteDatabase db) {
        Cursor c = db.query("SELECT id, evento, msg, phone, timestamp, typeof(timestamp) FROM event_log ORDER BY id");
        assertEquals(4, c.getCount());

        c.moveToNext();
        assertEquals(1, c.getLong(0));
        assertEquals("incoming_sms", c.getString(1));
        assertEquals("hola", c.getString(2));
        assertEquals("5550001111", c.getString(3));
        assertEquals("2026-10-15 23:46:44", c.getString(4));
        assertEquals("text", c.getString(5));

        c.moveToNext();
        assertEquals(2, c.getLong(0));
        assertEquals("sent_server", c.getString(1));

        c.moveToNext();
        assertEquals("gateway_revoked", c.getString(1));
        assertEquals("", c.getString(3));

        c.moveToNext();
        assertEquals(4, c.getLong(0));
        assertNull(c.getString(2));
        assertNull(c.getString(4));
        c.close();
    }

    @Test
    public void keepsRowsFromEventLogSql() throws Exception {
        // EventLog_SQL.onCreate y Httppostaux.SaveEventLog.
        createV5("CREATE TABLE event_log (" +
                " id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                " evento TEXT NULL, msg TEXT NULL, phone TEXT NULL, timestamp TIMESTAMP NULL)", false);
        assertRowsKept(migrate());
    }

    @Test
    public void keepsRowsFromEventLogFragment() throws Exception {
        // El CREATE de EventLogFragment.getcalls (timestamp TEXT, id sin NOT NULL).
        createV5("CREATE TABLE event_log (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "evento TEXT, msg TEXT, phone TEXT, timestamp TEXT)", true);
        assertRowsKept(migrateKeepingSmsList());
    }

    @Test
    public void newIdsContinueAfterTheOldOnes() throws Exception {
        createV5("CREATE TABLE event_log (" +
                " id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                " evento TEXT NULL, msg TEXT NULL, phone TEXT NULL, timestamp TIMESTAMP NULL)", false);
        SupportSQLiteDatabase db = migrate();
        db.execSQL("INSERT INTO event_log (evento) VALUES ('nuevo')");
        Cursor c = db.query("SELECT max(id) FROM event_log");
        c.moveToFirst();
        assertEquals(5, c.getLong(0));
        c.close();
    }

    @Test
    public void createsTheTableWhenVersion5HadNone() throws Exception {
        // Una base que creó primero el viejo SMSList_SQL, sin event_log.
        createV5(null, true);
        SupportSQLiteDatabase db = migrateKeepingSmsList();
        Cursor c = db.query("SELECT count(*) FROM event_log");
        c.moveToFirst();
        assertEquals(0, c.getLong(0));
        c.close();
    }
}
