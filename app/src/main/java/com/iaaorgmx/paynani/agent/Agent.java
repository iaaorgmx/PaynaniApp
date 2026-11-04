package com.iaaorgmx.paynani.agent;

import java.util.Collections;
import java.util.List;

/**
 * El agente de IA, como lo da `agent.get` (paynani #692, SMS_GATEWAY.md §13): lo mismo que la página
 * `/agent` de PaynaniWeb. Lo que la pasarela no manda queda en null.
 */
public final class Agent {

    public static final class Activity {
        public final String at, channel, state, who, what;

        public Activity(String at, String channel, String state, String who, String what) {
            this.at = at;
            this.channel = channel;
            this.state = state;
            this.who = who;
            this.what = what;
        }
    }

    /** La pasarela de SMS, como `phone` de /agent; null sin teléfono emparejado. */
    public static final class Phone {
        public final boolean online;
        public final String model, appVersion;
        public final Integer battery, lastSeenAgeS;

        public Phone(boolean online, String model, String appVersion, Integer battery, Integer lastSeenAgeS) {
            this.online = online;
            this.model = model;
            this.appVersion = appVersion;
            this.battery = battery;
            this.lastSeenAgeS = lastSeenAgeS;
        }
    }

    public final String name, email, runtimeLabel, status;
    /** watch, push o spool; null si la pasarela no lo manda. */
    public final String kind;
    public final boolean sessionLive;
    public final String sessionArmedAt, sessionExpiresAt, lastAccepted;
    public final int replied, closed, waiting;
    public final String lastReplyAt, lastReplyWho, lastReplyChannel;
    public final Integer replyMedianS;
    public final String version, latestVersion, dispatcher, listener;
    public final int rosterCount;
    public final Phone phone;
    /** null sin WhatsApp o Telegram configurado. */
    public final String whatsapp, telegramState, telegramBot;
    public final List<Activity> activity;

    public Agent(String name, String email, String runtimeLabel, String status, String kind,
                 boolean sessionLive, String sessionArmedAt, String sessionExpiresAt, String lastAccepted,
                 int replied, int closed, int waiting, String lastReplyAt, String lastReplyWho,
                 String lastReplyChannel, Integer replyMedianS, String version, String latestVersion,
                 String dispatcher, String listener, int rosterCount, Phone phone, String whatsapp,
                 String telegramState, String telegramBot, List<Activity> activity) {
        this.name = name;
        this.email = email;
        this.runtimeLabel = runtimeLabel;
        this.status = AgentRules.status(status);
        this.kind = kind;
        this.sessionLive = sessionLive;
        this.sessionArmedAt = sessionArmedAt;
        this.sessionExpiresAt = sessionExpiresAt;
        this.lastAccepted = lastAccepted;
        this.replied = replied;
        this.closed = closed;
        this.waiting = waiting;
        this.lastReplyAt = lastReplyAt;
        this.lastReplyWho = lastReplyWho;
        this.lastReplyChannel = lastReplyChannel;
        this.replyMedianS = replyMedianS;
        this.version = version;
        this.latestVersion = latestVersion;
        this.dispatcher = dispatcher;
        this.listener = listener;
        this.rosterCount = rosterCount;
        this.phone = phone;
        this.whatsapp = whatsapp;
        this.telegramState = telegramState;
        this.telegramBot = telegramBot;
        this.activity = activity == null ? Collections.emptyList() : activity;
    }
}
