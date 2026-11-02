package com.iaaorgmx.paynani.gateway.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Un SMS ya encolado (o de la otra SIM), para reconocer su duplicado (#46, #51).
 * La tabla no tenía llave primaria; Room pide una, así que se agrega n.
 */
@Entity(tableName = "seen_in", indices = {@Index(name = "seen_in_key_sent", value = {"key", "sent_ms"})})
public class SeenSms {

    @PrimaryKey(autoGenerate = true)
    public long n;

    @NonNull
    public String key = "";

    @ColumnInfo(name = "sent_ms")
    public long sentMs;

    @NonNull
    public String text = "";

    @NonNull
    public String id = "";

    public long at;
}
