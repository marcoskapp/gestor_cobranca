package br.com.gestorcobranca.ingestion.domain.service;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conversão dos formatos usados pelos credores brasileiros.
 * <ul>
 *   <li>Datas: {@code dd/MM/aaaa} (aceita dia e mês com um dígito), {@code dd-MM-aaaa} e ISO
 *       {@code aaaa-MM-dd} (com ou sem horário, que é descartado). Datas inexistentes (31/02) e
 *       anos fora de 1900–2199 são recusados; ano com dois dígitos é ambíguo e recusado.</li>
 *   <li>Valores monetários: {@code format.AmountParser}, com a convenção decidida por coluna
 *       ({@code docs/tecnico/desenho/importacao.md} §2).</li>
 * </ul>
 */
public final class BrazilianFormats {

    private static final DateTimeFormatter BR_SLASH = DateTimeFormatter.ofPattern("d/M/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter BR_DASH = DateTimeFormatter.ofPattern("d-M-uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern BR_DATE = Pattern.compile("\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2199;


    private static final Set<String> TRUE_VALUES = Set.of("S", "SIM", "Y", "YES", "1", "TRUE", "VERDADEIRO", "X");
    private static final Set<String> FALSE_VALUES = Set.of("N", "NAO", "NO", "0", "FALSE", "FALSO");

    private BrazilianFormats() {
    }

    public static Optional<LocalDate> parseDate(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        try {
            LocalDate date;
            if (BR_DATE.matcher(value).matches()) {
                date = LocalDate.parse(value, value.contains("/") ? BR_SLASH : BR_DASH);
            } else {
                var iso = ISO_DATE.matcher(value);
                if (!iso.matches()) {
                    return Optional.empty();
                }
                date = LocalDate.parse(iso.group(1), DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT));
            }
            return date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR ? Optional.empty() : Optional.of(date);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** Inteiro positivo ou zero (aceita zeros à esquerda e o ".0" de planilhas); vazio se inválido. */
    public static Optional<Integer> parseInteger(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
        }
        if (!value.matches("\\d{1,9}")) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(value));
    }

    /** S/N, Sim/Não, 1/0, X, true/false; vazio se não reconhecido. */
    public static Optional<Boolean> parseFlag(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        if (TRUE_VALUES.contains(value)) {
            return Optional.of(true);
        }
        if (FALSE_VALUES.contains(value)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }
}
