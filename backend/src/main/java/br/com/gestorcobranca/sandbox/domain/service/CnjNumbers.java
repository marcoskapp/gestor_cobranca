package br.com.gestorcobranca.sandbox.domain.service;

import java.math.BigInteger;

/**
 * Número único de processo (Resolução CNJ 65/2008: {@code NNNNNNN-DD.AAAA.J.TR.OOOO}) para processos
 * <strong>fictícios</strong> do sandbox, com dígito verificador válido (módulo 97, ISO 7064) — o cadastro de ações
 * do jurídico valida o número como em produção.
 */
public final class CnjNumbers {

    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private CnjNumbers() {
    }

    /**
     * @param sequential até 7 dígitos (completado com zeros)
     * @param year       ano de ajuizamento
     * @param segment    segmento da Justiça (8 = estadual)
     * @param court      tribunal (2 dígitos; 26 = TJSP)
     * @param origin     unidade de origem (4 dígitos)
     */
    public static String format(long sequential, int year, int segment, int court, int origin) {
        String n = "%07d".formatted(sequential);
        String a = "%04d".formatted(year);
        String j = String.valueOf(segment);
        String tr = "%02d".formatted(court);
        String o = "%04d".formatted(origin);
        int dd = 98 - new BigInteger(n + a + j + tr + o + "00").mod(NINETY_SEVEN).intValue();
        return "%s-%02d.%s.%s.%s.%s".formatted(n, dd, a, j, tr, o);
    }

    /** Confere o dígito verificador de um número formatado. */
    public static boolean isValid(String formatted) {
        String digits = formatted.replaceAll("\\D", "");
        if (digits.length() != 20) {
            return false;
        }
        String reordered = digits.substring(0, 7) + digits.substring(9) + digits.substring(7, 9);
        return new BigInteger(reordered).mod(NINETY_SEVEN).intValue() == 1;
    }
}
