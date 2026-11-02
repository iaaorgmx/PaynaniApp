package com.iaaorgmx.paynani.gateway.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Un SMS que el dueño mandó desde «Mensajes» (PaynaniApp#111, APP-C2). Se guarda
 * antes de mandarlo, así su estado no se pierde si la app se cierra.
 */
@Entity(tableName = "human_sends", indices = {@Index("to_raw")})
public class HumanSend {

    /** h_ + 32 hex (SMS_GATEWAY.md §3). */
    @PrimaryKey
    @NonNull
    public String local_id = "";

    /** El número como lo escribió o lo eligió el dueño. */
    @NonNull
    public String to_raw = "";

    public String to_e164;

    @NonNull
    public String text = "";

    /** sending, sent, delivered o failed. */
    @NonNull
    public String status = "";

    public int parts;

    public int sim_slot = -1;

    /** El id del último sms.in de la conversación, si lo hay. */
    public String in_reply_to;

    public String error;

    public long created_at;

    public long updated_at;
}
