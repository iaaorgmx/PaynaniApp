package com.iaaorgmx.paynani.gateway.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/** Una notificación de Google Messages por revisar a los 60 s (#55). */
@Entity(tableName = "unseen_pending", primaryKeys = {"nkey", "when_ms"})
public class UnseenPending {

    @NonNull
    public String nkey = "";

    @ColumnInfo(name = "when_ms")
    public long whenMs;

    @ColumnInfo(name = "posted_ms")
    public long postedMs;

    @NonNull
    public String title = "";

    @ColumnInfo(defaultValue = "0")
    public int done;
}
