package com.iaaorgmx.paynani.gateway.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Una orden sms.out ya recibida y su último estado (SMS_GATEWAY.md §4). */
@Entity(tableName = "orders")
public class Order {

    @PrimaryKey
    @NonNull
    public String id = "";

    @NonNull
    public String status = "";

    public long at;
}
