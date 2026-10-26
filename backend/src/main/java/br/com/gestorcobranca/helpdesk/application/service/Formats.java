package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação brasileira para os cartões exibidos ao usuário (o modelo recebe números e datas ISO). */
final class Formats {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private Formats() {
    }

    static String money(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        return NumberFormat.getCurrencyInstance(PT_BR).format(value).replace(' ', ' ');
    }

    static String date(LocalDate value) {
        return value == null ? "—" : DATE.format(value);
    }

    static String dateTime(Instant value) {
        return value == null ? "—" : DATE_TIME.format(value.atZone(TimeConfig.BUSINESS_ZONE));
    }

    /** Data no fuso de operação, para o modelo (ISO). */
    static String isoDate(Instant value) {
        return value == null ? null : value.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate().toString();
    }
}
