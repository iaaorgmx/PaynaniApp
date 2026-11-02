package com.iaaorgmx.paynani.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Una fila de la bitácora (tabla event_log). */
@Entity(tableName = "event_log")
public class EventLog {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String evento;

    public String msg;

    public String phone;

    /** «yyyy-MM-dd HH:mm:ss», hora local. */
    public String timestamp;
}
