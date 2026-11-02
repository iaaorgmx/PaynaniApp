package com.iaaorgmx.paynani.gateway.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Una orden del dueño (w_) o de la API (a_) que la app envió, para marcarla en
 * «Mensajes» (paynani PRD #489 FR-19, PRD #491 FR-19). Las del agente no se guardan.
 */
@Entity(tableName = "order_marks", indices = {@Index("to_e164")})
public class OrderMark {

    @PrimaryKey
    @NonNull
    public String order_id = "";

    /** owner o api. */
    @NonNull
    public String origin = "";

    /** El nombre del token, sólo en las de la API. */
    public String token;

    @NonNull
    public String to_e164 = "";

    @NonNull
    public String text = "";

    public long at;
}
