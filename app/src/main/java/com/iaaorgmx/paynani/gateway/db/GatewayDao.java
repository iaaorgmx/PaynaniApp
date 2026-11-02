package com.iaaorgmx.paynani.gateway.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/** Las consultas de GatewayQueue, una por una como estaban en SQL (#105). */
@Dao
public interface GatewayDao {

    // --- outq ---------------------------------------------------------------

    /** -1 si ese id ya estaba. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertOut(OutItem item);

    @Query("SELECT * FROM outq WHERE last_sent_at = 0 OR last_sent_at < :before ORDER BY created_at LIMIT :limit")
    List<OutItem> due(long before, int limit);

    @Query("UPDATE outq SET last_sent_at = :now, attempts = attempts + 1 WHERE id = :id")
    void markSent(String id, long now);

    @Query("SELECT * FROM outq WHERE id = :id")
    OutItem getOut(String id);

    @Query("DELETE FROM outq WHERE id = :id")
    void removeOut(String id);

    @Query("DELETE FROM outq WHERE type = :type")
    int removeOutType(String type);

    @Query("UPDATE outq SET last_sent_at = 0")
    void resetSent();

    @Query("SELECT COUNT(*) FROM outq")
    int countOut();

    @Query("SELECT COUNT(*) FROM outq WHERE type = :type")
    int countOut(String type);

    @Query("DELETE FROM outq")
    void clearOut();

    // --- orders -------------------------------------------------------------

    @Query("SELECT status FROM orders WHERE id = :id")
    String orderStatus(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putOrder(Order order);

    // Sólo las del agente (o_): las del dueño y la API no cuentan para su tope (paynani #489 FR-18).
    @Query("SELECT COUNT(*) FROM orders WHERE status IN ('accepted','sent','delivered','failed') AND at > :after "
            + "AND id LIKE 'o\\_%' ESCAPE '\\'")
    int sentSince(long after);

    @Query("DELETE FROM orders")
    void clearOrders();

    // --- human_sends (PaynaniApp#111) ----------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertHuman(HumanSend row);

    @Query("SELECT * FROM human_sends WHERE local_id = :localId")
    HumanSend getHuman(String localId);

    @Query("UPDATE human_sends SET status = :status, error = :error, updated_at = :now WHERE local_id = :localId")
    void setHumanStatus(String localId, String status, String error, long now);

    @Query("SELECT * FROM human_sends WHERE to_raw = :toRaw OR to_e164 = :e164 ORDER BY created_at")
    List<HumanSend> humanTo(String toRaw, String e164);

    /** El id del último SMS recibido de esa llave (E.164 o remitente), para in_reply_to. */
    @Query("SELECT id FROM seen_in WHERE key = :key ORDER BY sent_ms DESC LIMIT 1")
    String lastSeenId(String key);

    // --- seen_in ------------------------------------------------------------

    @Query("DELETE FROM seen_in WHERE at < :before")
    void forgetSeenBefore(long before);

    @Query("SELECT * FROM seen_in WHERE key = :key AND sent_ms = :sentMs")
    List<SeenSms> seen(String key, long sentMs);

    @Insert
    void insertSeen(SeenSms row);

    @Query("SELECT EXISTS(SELECT 1 FROM seen_in WHERE at BETWEEN :from AND :to)")
    boolean seenBetween(long from, long to);

    @Query("DELETE FROM seen_in WHERE id = :id")
    void forgetSeen(String id);

    @Query("DELETE FROM seen_in")
    void clearSeen();

    // --- unseen_pending -----------------------------------------------------

    @Query("DELETE FROM unseen_pending WHERE done = 1 AND posted_ms < :before")
    void forgetUnseenBefore(long before);

    /** -1 si esa pareja (nkey, when_ms) ya estaba. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertUnseen(UnseenPending row);

    @Query("SELECT * FROM unseen_pending WHERE done = 0 AND posted_ms <= :postedBefore ORDER BY posted_ms")
    List<UnseenPending> dueUnseen(long postedBefore);

    @Query("UPDATE unseen_pending SET done = 1 WHERE nkey = :nkey AND when_ms = :whenMs")
    void doneUnseen(String nkey, long whenMs);

    @Query("DELETE FROM unseen_pending")
    void clearUnseen();

    // --- marcas de «Mensajes» para órdenes del dueño y de la API (paynani #489, #491) ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertMark(OrderMark mark);

    @Query("SELECT * FROM order_marks WHERE to_e164 = :e164 ORDER BY at")
    List<OrderMark> marksTo(String e164);

    @Query("DELETE FROM order_marks WHERE at < :before")
    void forgetMarksBefore(long before);

    @Query("DELETE FROM order_marks")
    void clearMarks();
}
