package br.com.gestorcobranca.ingestion.domain.service.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Valor em reais no padrão brasileiro para mensagens ({@code R$ 1.234,56}). */
final class Brl {

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private Brl() {
    }

    static String format(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        return "R$ " + format.format(value.setScale(2, RoundingMode.HALF_EVEN));
    }

    static String format(double value) {
        return format(BigDecimal.valueOf(value));
    }
}
