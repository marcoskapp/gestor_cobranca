package br.com.gestorcobranca.analytics.domain.service;

/**
 * Proteção contra injeção de fórmulas (CSV/XLSX injection): texto que começa com {@code = + - @},
 * tabulação ou retorno de carro recebe um apóstrofo à frente e é tratado como texto pela planilha.
 * Números (inclusive negativos) passam inalterados.
 */
public final class SpreadsheetSafety {

    private SpreadsheetSafety() {
    }

    public static String neutralize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        boolean risky = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
        if (risky && !value.matches("-?\\d+(\\.\\d+)?")) {
            return "'" + value;
        }
        return value;
    }
}
