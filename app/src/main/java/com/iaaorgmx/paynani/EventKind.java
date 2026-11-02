package com.iaaorgmx.paynani;

/**
 * El título y el ícono de cada evento del registro (#65), los mismos en la lista
 * y en su detalle (#113). Un evento que esta versión no conoce va con el avatar
 * de respaldo y su nombre tal cual.
 */
public final class EventKind {

    private EventKind() {
    }

    /** El título del evento, o 0 si esta versión no lo conoce. */
    public static int title(String event) {
        switch (event == null ? "" : event) {
            case "rcs_unseen": return R.string.label_rcs_unseen;
            case "sent_server":
            case "sent_server_answered":
            case "sent_server_incoming":
            case "sent_server_outgoing": return R.string.label_switched_on;
            case "reply_contact": return R.string.label_autoreply;
            case "call_answered": return R.string.label_incoming_answered_call;
            case "call_not_answered": return R.string.label_incoming_notanswered_call;
            case "call_outgoing": return R.string.label_outgoing_call;
            case "incoming_sms": return R.string.label_incoming_sms;
            case "gateway_rejected": return R.string.label_gateway_rejected;
            case "gateway_revoked": return R.string.label_gateway_revoked;
            default: return 0;
        }
    }

    public static int icon(String event) {
        switch (event == null ? "" : event) {
            case "rcs_unseen": return R.drawable.ic_ev_report;
            case "sent_server": return R.drawable.ic_ev_cloud_upload;
            case "sent_server_answered":
            case "call_answered": return R.drawable.ic_ev_call;
            case "sent_server_incoming":
            case "call_not_answered": return R.drawable.ic_ev_phone_missed;
            case "sent_server_outgoing":
            case "call_outgoing": return R.drawable.ic_ev_call_made;
            case "reply_contact": return R.drawable.ic_ev_send;
            case "incoming_sms": return R.drawable.ic_ev_sms;
            case "gateway_rejected":
            case "gateway_revoked": return R.drawable.ic_ev_error;
            default: return R.drawable.ic_ev_person;
        }
    }

    /** Si el número del evento es a quién se le envió o llamó («Para»), no de quién llegó. */
    public static boolean outgoing(String event) {
        return "reply_contact".equals(event) || "call_outgoing".equals(event) || "sent_server_outgoing".equals(event);
    }

    /** Si el evento es de un SMS, para ofrecer abrir su conversación. */
    public static boolean sms(String event) {
        return "incoming_sms".equals(event) || "sent_server".equals(event) || "reply_contact".equals(event)
                || "rcs_unseen".equals(event);
    }
}
