package br.com.gestorcobranca.scoring.domain.model;

import java.util.Locale;

/** Canal de contato da inteligência de contato (canais de origem fora da lista viram {@link #OTHER}). */
public enum ContactChannel {
    PHONE, WHATSAPP, SMS, EMAIL, OTHER;

    /** Canal dos eventos (PHONE, VOICE, WHATSAPP, SMS, EMAIL, IN_PERSON, PORTAL…); nulo ou desconhecido → OTHER. */
    public static ContactChannel of(String channel) {
        if (channel == null) {
            return OTHER;
        }
        return switch (channel.trim().toUpperCase(Locale.ROOT)) {
            case "PHONE", "VOICE", "CALL" -> PHONE;
            case "WHATSAPP" -> WHATSAPP;
            case "SMS" -> SMS;
            case "EMAIL", "E_MAIL" -> EMAIL;
            default -> OTHER;
        };
    }

    /** Canal de mensagem (WhatsApp, SMS, e-mail): a mensagem recebida pode ser resposta a uma tentativa. */
    public boolean isMessage() {
        return this == WHATSAPP || this == SMS || this == EMAIL;
    }
}
