package com.iaaorgmx.paynani.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EventLogDao {

    @Insert
    long insert(EventLog row);

    /** Lo que muestra EventLogFragment: los últimos 7 días, lo más nuevo primero. */
    @Query("SELECT * FROM event_log WHERE timestamp > date('now','-7 days') ORDER BY timestamp DESC")
    List<EventLog> lastWeek();
}
