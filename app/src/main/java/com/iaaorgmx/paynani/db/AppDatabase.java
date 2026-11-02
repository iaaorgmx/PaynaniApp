package com.iaaorgmx.paynani.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * La base DBPaynaniAppNew1 con Room (#105). Es el mismo archivo que usaba
 * EventLog_SQL en la versión 5; la 6 es la primera de Room.
 */
@Database(entities = {EventLog.class}, version = 6, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    public static final String NAME = "DBPaynaniAppNew1";

    public abstract EventLogDao eventLogDao();

    /**
     * Rehace event_log con el esquema de Room y copia todas las filas. En la 5
     * la tabla podía venir de tres CREATE distintos (TIMESTAMP o TEXT) o no
     * existir, si la base la creó primero el viejo SMSList_SQL.
     */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS event_log (" +
                    " id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    " evento TEXT NULL," +
                    " msg TEXT NULL," +
                    " phone TEXT NULL," +
                    " timestamp TIMESTAMP NULL)");
            db.execSQL("CREATE TABLE event_log_new (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`evento` TEXT, `msg` TEXT, `phone` TEXT, `timestamp` TEXT)");
            db.execSQL("INSERT INTO event_log_new (id, evento, msg, phone, timestamp) " +
                    "SELECT id, evento, msg, phone, timestamp FROM event_log");
            db.execSQL("DROP TABLE event_log");
            db.execSQL("ALTER TABLE event_log_new RENAME TO event_log");
        }
    };

    private static volatile AppDatabase instance;

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, NAME)
                            .addMigrations(MIGRATION_5_6)
                            .build();
                }
            }
        }
        return instance;
    }
}
