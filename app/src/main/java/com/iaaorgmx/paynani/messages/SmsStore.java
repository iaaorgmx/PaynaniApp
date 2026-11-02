package com.iaaorgmx.paynani.messages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;

import androidx.core.content.ContextCompat;

import com.iaaorgmx.paynani.gateway.Sims;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura de los SMS del teléfono para la pantalla «Mensajes» (#83).
 *
 * Sólo lee el almacén del sistema (content://sms): ahí están los recibidos y
 * también lo que envía la pasarela, porque Android guarda en «enviados» lo que
 * manda con SmsManager una app que no es la de SMS predeterminada. No escribe
 * nada: ni borra ni marca como leído. RCS y MMS no se leen.
 */
public final class SmsStore {

    /** Mensajes por página del hilo. */
    public static final int PAGE = 50;

    /** Los borradores (tipo 3) no se muestran: no se enviaron. */
    private static final String NOT_DRAFT = Telephony.Sms.TYPE + "!=" + Telephony.Sms.MESSAGE_TYPE_DRAFT;

    /** Del más nuevo al más viejo; el _id desempata los del mismo milisegundo. */
    private static final String NEWEST_FIRST = Telephony.Sms.DATE + " DESC, " + Telephony.Sms._ID + " DESC";

    private static final String[] PROJECTION = {
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS,
            Telephony.Sms.SUBSCRIPTION_ID,
    };

    public static final class Msg {
        public long id;
        public long threadId;
        public String address;
        public String body;
        public long date;
        public int type;
        public boolean read;
        public int status;
        public int subId;

        /** Lo mandó este teléfono (enviado, por enviar o fallido), no lo recibió. */
        public boolean outgoing() {
            return type != Telephony.Sms.MESSAGE_TYPE_INBOX;
        }

        public boolean failed() {
            return type == Telephony.Sms.MESSAGE_TYPE_FAILED
                    || (type == Telephony.Sms.MESSAGE_TYPE_SENT && status >= Telephony.Sms.STATUS_FAILED);
        }

        public boolean pending() {
            return type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED;
        }

        public boolean delivered() {
            return type == Telephony.Sms.MESSAGE_TYPE_SENT && status == Telephony.Sms.STATUS_COMPLETE;
        }
    }

    /** Una conversación de la lista: su último mensaje y si tiene recibidos sin leer. */
    public static final class Conversation {
        public Msg last;
        public boolean unread;
    }

    private final Context context;
    /** Nombres ya buscados; {@link #NO_NAME} cuando el número no está en contactos. */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private static final String NO_NAME = "";
    private final Map<Integer, Integer> slots = new HashMap<>();

    public SmsStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean canRead() {
        return granted(Manifest.permission.READ_SMS);
    }

    public boolean canReadContacts() {
        return granted(Manifest.permission.READ_CONTACTS);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Columnas del índice de conversaciones: sin cuerpo ni dirección. */
    private static final String[] LIGHT = {
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.TYPE, Telephony.Sms.READ,
    };

    /** Conversaciones por página en «Mensajes» (#112). */
    public static final int CONV_PAGE = 30;

    /** Una conversación del índice: su hilo, el _id de su último SMS y si tiene recibidos sin leer. */
    public static final class ConvRef {
        public final long threadId;
        public final long lastId;
        public final boolean unread;

        ConvRef(long threadId, long lastId, boolean unread) {
            this.threadId = threadId;
            this.lastId = lastId;
            this.unread = unread;
        }
    }

    /**
     * El índice de conversaciones, la más reciente primero (#112). Recorre todos
     * los SMS pero solo con columnas ligeras; el cuerpo y la dirección se leen
     * después, por página, con {@link #conversations(List)}. Con miles de SMS,
     * leer todos completos dejaba «Mensajes» en blanco más de un minuto.
     */
    public List<ConvRef> conversationIndex() {
        if (!canRead()) return new ArrayList<>();
        LinkedHashMap<Long, Long> lastId = new LinkedHashMap<>();
        Set<Long> unread = new HashSet<>();
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, LIGHT,
                NOT_DRAFT, null, NEWEST_FIRST)) {
            while (c != null && c.moveToNext()) {
                long thread = c.getLong(1);
                if (!lastId.containsKey(thread)) lastId.put(thread, c.getLong(0));
                if (c.getInt(2) == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(3) == 0) unread.add(thread);
            }
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
        List<ConvRef> out = new ArrayList<>(lastId.size());
        for (Map.Entry<Long, Long> e : lastId.entrySet()) {
            out.add(new ConvRef(e.getKey(), e.getValue(), unread.contains(e.getKey())));
        }
        return out;
    }

    /** Las conversaciones de una página del índice, en el mismo orden, con su último SMS completo. */
    public List<Conversation> conversations(List<ConvRef> refs) {
        List<Conversation> out = new ArrayList<>();
        if (refs.isEmpty() || !canRead()) return out;
        StringBuilder in = new StringBuilder();
        for (ConvRef r : refs) in.append(in.length() == 0 ? "" : ",").append(r.lastId);
        Map<Long, Msg> byId = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, PROJECTION,
                Telephony.Sms._ID + " IN (" + in + ")", null, null)) {
            while (c != null && c.moveToNext()) {
                Msg m = read(c);
                byId.put(m.id, m);
            }
        } catch (SecurityException e) {
            return out;
        }
        for (ConvRef r : refs) {
            Msg m = byId.get(r.lastId);
            if (m == null) continue; // se borró después de armar el índice
            Conversation conv = new Conversation();
            conv.last = m;
            conv.unread = r.unread;
            out.add(conv);
        }
        return out;
    }

    /**
     * Una página del hilo, del más viejo al más nuevo: hasta PAGE mensajes
     * anteriores al mensaje ({@code beforeDate}, {@code beforeId}); con
     * Long.MAX_VALUE en ambos, los más recientes. El _id evita perder un
     * mensaje con el mismo milisegundo que el corte de la página.
     */
    public List<Msg> page(long threadId, long beforeDate, long beforeId) {
        String date = String.valueOf(beforeDate);
        return query(Telephony.Sms.THREAD_ID + "=? AND (" + Telephony.Sms.DATE + "<? OR ("
                        + Telephony.Sms.DATE + "=? AND " + Telephony.Sms._ID + "<?))",
                new String[]{String.valueOf(threadId), date, date, String.valueOf(beforeId)}, PAGE);
    }

    /** Todo el hilo desde {@code since} (incluido), del más viejo al más nuevo. */
    public List<Msg> since(long threadId, long since) {
        return query(Telephony.Sms.THREAD_ID + "=? AND " + Telephony.Sms.DATE + ">=?",
                new String[]{String.valueOf(threadId), String.valueOf(since)}, Integer.MAX_VALUE);
    }

    private List<Msg> query(String selection, String[] args, int limit) {
        List<Msg> out = new ArrayList<>();
        if (!canRead()) return out;
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, PROJECTION,
                "(" + selection + ") AND " + NOT_DRAFT, args, NEWEST_FIRST)) {
            while (c != null && out.size() < limit && c.moveToNext()) out.add(read(c));
        } catch (SecurityException e) {
            return out;
        }
        Collections.reverse(out);
        return out;
    }

    private static Msg read(Cursor c) {
        Msg m = new Msg();
        m.id = c.getLong(0);
        m.threadId = c.getLong(1);
        m.address = c.isNull(2) ? "" : c.getString(2);
        m.body = c.isNull(3) ? "" : c.getString(3);
        m.date = c.getLong(4);
        m.type = c.getInt(5);
        m.read = c.getInt(6) != 0;
        m.status = c.isNull(7) ? Telephony.Sms.STATUS_NONE : c.getInt(7);
        m.subId = c.isNull(8) ? -1 : c.getInt(8);
        return m;
    }

    /** El nombre del contacto, o null si no hay permiso o no está en contactos. */
    public String contactName(String address) {
        if (address == null || address.isEmpty() || !canReadContacts()) return null;
        String cached = names.get(address);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String name = null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException e) {
            name = null;
        }
        names.put(address, name == null ? NO_NAME : name);
        return name;
    }

    /**
     * El hilo de SMS de un número, o -1 si no hay (#113: «Abrir conversación» desde el
     * registro). Compara como Android los números (PHONE_NUMBERS_EQUAL) y, si el
     * proveedor no lo acepta, exacto. Fuera del hilo principal.
     */
    public long threadOf(String address) {
        if (address == null || address.isEmpty() || !canRead()) return -1;
        String[] where = {"PHONE_NUMBERS_EQUAL(" + Telephony.Sms.ADDRESS + ", ?)", Telephony.Sms.ADDRESS + "=?"};
        for (String w : where) {
            try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                    new String[]{Telephony.Sms.THREAD_ID}, w + " AND " + NOT_DRAFT, new String[]{address}, NEWEST_FIRST)) {
                if (c != null && c.moveToFirst()) return c.getLong(0);
                if (c != null) return -1;
            } catch (SecurityException e) {
                return -1;
            } catch (RuntimeException e) {
                // PHONE_NUMBERS_EQUAL no existe en este proveedor: se intenta exacto.
            }
        }
        return -1;
    }

    /**
     * La URI del contacto de ese número para ACTION_VIEW (#134, FR-1), o null si no está en
     * contactos o no hay permiso. Fuera del hilo principal.
     */
    public Uri contactUri(String address) {
        if (address == null || address.isEmpty() || !canReadContacts()) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY}, null, null, null)) {
            if (c != null && c.moveToFirst()) return ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1));
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** El primer correo del contacto de ese número (PRD #137, FR-9), o null. Fuera del hilo principal. */
    public String contactEmail(String address) {
        if (address == null || address.isEmpty() || !canReadContacts()) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = context.getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            try (Cursor e = context.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?", new String[]{String.valueOf(c.getLong(0))},
                    ContactsContract.CommonDataKinds.Email.IS_PRIMARY + " DESC")) {
                return e != null && e.moveToFirst() ? e.getString(0) : null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Olvida el nombre ya buscado: al volver de la app de contactos puede haber uno nuevo (#134, FR-2). */
    public void forgetName(String address) {
        if (address != null) names.remove(address);
    }

    /** El nombre si ya se buscó, sin consultar contactos: para el hilo principal (#112). */
    public String cachedName(String address) {
        String cached = address == null ? null : names.get(address);
        return cached == null || cached.isEmpty() ? null : cached;
    }

    /**
     * El número para mostrar. Los códigos cortos (22395) y los remitentes con
     * letras van tal cual; los números completos, con el formato del país.
     */
    public String displayNumber(String address) {
        if (address == null) return "";
        String digits = address.replaceAll("[^0-9]", "");
        boolean phone = address.startsWith("+") || digits.length() >= 10;
        if (!phone || !address.matches("[0-9+()\\-. ]+")) return address;
        String formatted = PhoneNumberUtils.formatNumber(address, Sims.region(context, null));
        return formatted != null ? formatted : address;
    }

    /** Nombre del contacto o número formateado. */
    public String title(String address) {
        String name = contactName(address);
        return name != null ? name : displayNumber(address);
    }

    /** Ranura (1, 2…) de la SIM del mensaje, o 0 si no se sabe. */
    public int slot(int subId) {
        if (subId < 0) return 0;
        Integer cached = slots.get(subId);
        if (cached != null) return cached;
        SubscriptionInfo info = Sims.bySubId(context, subId);
        int slot = info != null ? info.getSimSlotIndex() + 1 : 0;
        slots.put(subId, slot);
        return slot;
    }

    /** ¿Hay más de una SIM activa? Sólo entonces se muestra la SIM de cada mensaje. */
    public boolean dualSim() {
        return Sims.active(context).size() > 1;
    }
}
