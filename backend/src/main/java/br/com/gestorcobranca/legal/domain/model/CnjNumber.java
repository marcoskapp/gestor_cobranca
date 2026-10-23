package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Numeração única de processos (Resolução CNJ 65/2008): {@code NNNNNNN-DD.AAAA.J.TR.OOOO}, com 20
 * dígitos — sequencial (7), dígito verificador (2), ano do ajuizamento (4), segmento do Judiciário
 * (1), tribunal (2) e unidade de origem (4). O dígito verificador é calculado pelo módulo 97
 * (ISO 7064): {@code DD = 98 − (NNNNNNN AAAA J TR OOOO × 100 mod 97)}, o que equivale a exigir que o
 * número reordenado {@code NNNNNNN AAAA J TR OOOO DD} tenha resto 1 na divisão por 97.
 *
 * @param digits os 20 dígitos, sem máscara
 */
public record CnjNumber(String digits) {

    private static final BigInteger MOD = BigInteger.valueOf(97);

    public CnjNumber {
        Objects.requireNonNull(digits, "digits");
        if (!digits.matches("\\d{20}")) {
            throw invalid("Número CNJ deve ter 20 dígitos (NNNNNNN-DD.AAAA.J.TR.OOOO)");
        }
        int segment = digits.charAt(13) - '0';
        if (segment < 1 || segment > 9) {
            throw invalid("Segmento do Judiciário inválido no número CNJ");
        }
        if (!checkDigitsValid(digits)) {
            throw invalid("Dígito verificador do número CNJ inválido");
        }
    }

    /** Aceita o número com ou sem máscara. */
    public static CnjNumber parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleException("lawsuit.cnj_required", "Número CNJ obrigatório");
        }
        String trimmed = raw.trim();
        if (!trimmed.matches("[\\d.\\-\\s]+")) {
            throw invalid("Número CNJ contém caracteres inválidos");
        }
        return new CnjNumber(trimmed.replaceAll("\\D", ""));
    }

    /** Dígito verificador correto para os 18 dígitos restantes (sequencial, ano, segmento, tribunal, origem). */
    public static String checkDigitsFor(String sequential, String year, String segment, String court, String origin) {
        BigInteger base = new BigInteger(sequential + year + segment + court + origin + "00");
        int dd = 98 - base.mod(MOD).intValue();
        return "%02d".formatted(dd);
    }

    private static boolean checkDigitsValid(String d) {
        String reordered = d.substring(0, 7) + d.substring(9, 20) + d.substring(7, 9);
        return new BigInteger(reordered).mod(MOD).intValue() == 1;
    }

    public String sequential() {
        return digits.substring(0, 7);
    }

    public int year() {
        return Integer.parseInt(digits.substring(9, 13));
    }

    /** J: 1 STF, 2 CNJ, 3 STJ, 4 Justiça Federal, 5 Trabalho, 6 Eleitoral, 7 Militar da União, 8 Estadual, 9 Militar Estadual. */
    public int segment() {
        return digits.charAt(13) - '0';
    }

    public String court() {
        return digits.substring(14, 16);
    }

    /** Código do tribunal no formato {@code J.TR} (ex.: {@code 8.26} = TJSP), usado nas suspensões de prazo. */
    public String courtCode() {
        return segment() + "." + court();
    }

    public String origin() {
        return digits.substring(16, 20);
    }

    public String formatted() {
        return "%s-%s.%s.%s.%s.%s".formatted(digits.substring(0, 7), digits.substring(7, 9), digits.substring(9, 13),
                digits.substring(13, 14), digits.substring(14, 16), digits.substring(16, 20));
    }

    @Override
    public String toString() {
        return formatted();
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("lawsuit.cnj_invalid", message);
    }
}
