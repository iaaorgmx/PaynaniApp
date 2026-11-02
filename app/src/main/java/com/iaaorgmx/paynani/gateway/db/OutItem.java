package com.iaaorgmx.paynani.gateway.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Un mensaje para la pasarela (sms.in, call.*, sms.status, sms.unseen) que espera su ack. */
@Entity(tableName = "outq")
public class OutItem {

    @PrimaryKey
    @NonNull
    public String id = "";

    @NonNull
    public String type = "";

    @NonNull
    public String json = "";

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "last_sent_at", defaultValue = "0")
    public long lastSentAt;

    @ColumnInfo(defaultValue = "0")
    public int attempts;
}
