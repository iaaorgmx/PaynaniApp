package com.iaaorgmx.paynani.gateway.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * paynani_gateway.db con Room (#105). Es el mismo archivo que usaba el
 * SQLiteOpenHelper de GatewayQueue hasta la versión 3; la 4 es la primera de Room.
 */
@Database(entities = {OutItem.class, Order.class, SeenSms.class, UnseenPending.class, HumanSend.class,
        OrderMark.class}, version = 6, exportSchema = true)
public abstract class GatewayDatabase extends RoomDatabase {

    public static final String NAME = "paynani_gateway.db";

    public abstract GatewayDao dao();

    // Las de antes, tal como hacía onUpgrade: sólo crean tablas que faltan. Todas
    // las releases publicadas (0.6.3 a 0.6.6) ya traen la 3; éstas cubren un APK
    // de desarrollo más viejo.
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS seen_in (key TEXT NOT NULL, sent_ms INTEGER NOT NULL, "
                    + "text TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS seen_in_key_sent ON seen_in (key, sent_ms)");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS unseen_pending (nkey TEXT NOT NULL, when_ms INTEGER NOT NULL, "
                    + "posted_ms INTEGER NOT NULL, title TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (nkey, when_ms))");
        }
    };

    /**
     * Rehace las cuatro tablas con el esquema de Room y copia todas las filas.
     * Cambia poco: los id de outq y orders pasan a NOT NULL (siempre traían uno)
     * y seen_in gana la llave n, que Room exige.
     */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            rebuild(db, "outq",
                    "CREATE TABLE `outq_new` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `json` TEXT NOT NULL, "
                            + "`created_at` INTEGER NOT NULL, `last_sent_at` INTEGER NOT NULL DEFAULT 0, "
                            + "`attempts` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                    "id, type, json, created_at, last_sent_at, attempts", "id IS NOT NULL");
            rebuild(db, "orders",
                    "CREATE TABLE `orders_new` (`id` TEXT NOT NULL, `status` TEXT NOT NULL, "
                            + "`at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "id, status, at", "id IS NOT NULL");
            db.execSQL("DROP INDEX IF EXISTS seen_in_key_sent");
            rebuild(db, "seen_in",
                    "CREATE TABLE `seen_in_new` (`n` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`key` TEXT NOT NULL, `sent_ms` INTEGER NOT NULL, `text` TEXT NOT NULL, "
                            + "`id` TEXT NOT NULL, `at` INTEGER NOT NULL)",
                    "key, sent_ms, text, id, at", null);
            db.execSQL("CREATE INDEX IF NOT EXISTS `seen_in_key_sent` ON `seen_in` (`key`, `sent_ms`)");
            rebuild(db, "unseen_pending",
                    "CREATE TABLE `unseen_pending_new` (`nkey` TEXT NOT NULL, `when_ms` INTEGER NOT NULL, "
                            + "`posted_ms` INTEGER NOT NULL, `title` TEXT NOT NULL, "
                            + "`done` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`nkey`, `when_ms`))",
                    "nkey, when_ms, posted_ms, title, done", null);
        }
    };

    /** Los SMS que el dueño manda desde «Mensajes» (PaynaniApp#111). */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `human_sends` (`local_id` TEXT NOT NULL, `to_raw` TEXT NOT NULL, "
                    + "`to_e164` TEXT, `text` TEXT NOT NULL, `status` TEXT NOT NULL, `parts` INTEGER NOT NULL, "
                    + "`sim_slot` INTEGER NOT NULL, `in_reply_to` TEXT, `error` TEXT, `created_at` INTEGER NOT NULL, "
                    + "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`local_id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_human_sends_to_raw` ON `human_sends` (`to_raw`)");
        }
    };

    /** Las marcas de «Mensajes» para órdenes del dueño y de la API (paynani #489, #491). */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `order_marks` (`order_id` TEXT NOT NULL, `origin` TEXT NOT NULL, "
                    + "`token` TEXT, `to_e164` TEXT NOT NULL, `text` TEXT NOT NULL, `at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`order_id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_marks_to_e164` ON `order_marks` (`to_e164`)");
        }
    };

    private static void rebuild(SupportSQLiteDatabase db, String table, String createNew,
                                String columns, String where) {
        db.execSQL(createNew);
        db.execSQL("INSERT INTO " + table + "_new (" + columns + ") SELECT " + columns + " FROM " + table
                + (where == null ? "" : " WHERE " + where));
        db.execSQL("DROP TABLE " + table);
        db.execSQL("ALTER TABLE " + table + "_new RENAME TO " + table);
    }

    private static volatile GatewayDatabase instance;

    public static GatewayDatabase get(Context context) {
        if (instance == null) {
            synchronized (GatewayDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    GatewayDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                                    MIGRATION_5_6)
                            .build();
                }
            }
        }
        return instance;
    }
}
