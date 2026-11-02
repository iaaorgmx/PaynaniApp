package com.iaaorgmx.paynani;

/**
 * Lo que se sabe de la llamada entrante en curso, de RINGING a IDLE (PaynaniApp #151): el número
 * (de la primera difusión que lo traiga) y si Telecom dijo que era una llamada administrada.
 * CustomCallReceiver lo guarda en SharedPreferences entre difusiones; aquí sólo vive la regla.
 */
final class CallCycle {

    private int managed;
    private String number;

    CallCycle(int managed, String number) {
        this.managed = managed;
        this.number = number == null ? "" : number;
    }

    /** RINGING nuevo: nada de la llamada anterior cuenta. */
    void start() {
        managed = CallOrigin.MANAGED_UNKNOWN;
        number = "";
    }

    /**
     * Una difusión mientras suena o se contesta. La primera consulta de la llamada se toma tal
     * cual; las siguientes se juntan con {@link CallOrigin#merge}. Un número real se queda.
     */
    void observe(String incomingNumber, int managedNow, boolean first) {
        managed = first ? managedNow : CallOrigin.merge(managed, managedNow);
        if (incomingNumber != null && !incomingNumber.isEmpty()) number = incomingNumber;
    }

    /**
     * Al colgar: qué hacer con la llamada. Con número se reporta siempre; sin él, según Telecom
     * (y si no se supo, el registro de llamadas). Deja el ciclo limpio para la siguiente.
     */
    CallOrigin.Decision end() {
        CallOrigin.Decision d = number.isEmpty() ? CallOrigin.decide(managed) : CallOrigin.Decision.REPORT;
        managed = CallOrigin.MANAGED_UNKNOWN;
        number = "";
        return d;
    }

    int managed() {
        return managed;
    }

    String number() {
        return number;
    }
}
