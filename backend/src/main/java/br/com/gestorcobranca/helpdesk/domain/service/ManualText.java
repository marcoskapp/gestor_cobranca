package br.com.gestorcobranca.helpdesk.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolve os marcadores de parâmetro do manual ({@code {{debt.cumulativeWindow}}}) com os valores que a aplicação
 * aplica, com as mesmas regras do frontend ({@code core/onboarding/manual/manual-parameters.ts}): cada marcador é uma
 * <em>frase</em> do catálogo exportado com a base de conhecimento ({@code phrases}), ligada a um parâmetro
 * ({@code parameters}: formato de cada um) e escrita no singular ({@code one}), no zero ({@code zero}) e no caso
 * geral ({@code other}, com {@code {n}} no lugar do número).
 * <p>
 * Diferente da tela, aqui não há texto neutro: a base do chat é resolvida na subida com os valores da configuração, e
 * marcador desconhecido, frase sem parâmetro ou parâmetro sem valor impedem a subida — o chat ensinaria errado.
 */
public final class ManualText {

    /** Marcador no texto: {@code {{<modulo>.<frase>}}}. */
    public static final Pattern MARKER = Pattern.compile("\\{\\{([a-z]+\\.[a-zA-Z0-9]+)}}");

    /** Formato de exibição do número (o mesmo nome do frontend). */
    public enum Format {
        /** Inteiro sem separador (dias, segundos, MB). */
        INTEGER,
        /** Vírgula decimal com as casas que o valor tem, de 2 a 4, sem arredondar (ex.: 0,60; 0,625). */
        DECIMAL,
        /** Reais com milhar e duas casas (ex.: R$ 1.000,00). */
        MONEY;

        public static Format of(String value) {
            return switch (Objects.requireNonNull(value, "formato")) {
                case "integer" -> INTEGER;
                case "decimal" -> DECIMAL;
                case "money" -> MONEY;
                default -> throw new IllegalStateException("Formato de parâmetro do manual desconhecido: " + value);
            };
        }
    }

    /**
     * @param parameter chave do parâmetro ({@code debt.cumulativeWindowDays})
     * @param zero      texto para o valor 0 (opcional; só inteiros)
     * @param one       texto para o valor 1 (opcional; só inteiros)
     * @param other     texto geral, com {@code {n}} no lugar do número
     * @param neutral   texto sem número (usado na tela enquanto o valor não chega; aqui só validado)
     */
    public record Phrase(String parameter, String zero, String one, String other, String neutral) {
    }

    /** Casas do formato {@code DECIMAL}: as que o valor tem, de 2 a 4 (igual ao frontend). */
    static final int DECIMAL_MIN = 2;
    static final int DECIMAL_MAX = 4;

    private final Map<String, Phrase> phrases;
    private final Map<String, String> rendered;
    private final String valuesFingerprint;

    /**
     * @param phrases    frases pelo identificador do marcador
     * @param formats    formato de cada parâmetro ({@code integer}, {@code decimal}, {@code money})
     * @param values     valores efetivos da aplicação
     * @throws IllegalStateException frase sem parâmetro conhecido, parâmetro sem valor ou valor fora do formato
     */
    public ManualText(Map<String, Phrase> phrases, Map<String, String> formats, Map<String, BigDecimal> values) {
        this.phrases = Map.copyOf(phrases);
        java.util.HashMap<String, String> out = new java.util.HashMap<>();
        TreeSet<String> missing = new TreeSet<>();
        java.util.TreeMap<String, BigDecimal> used = new java.util.TreeMap<>();
        this.phrases.forEach((id, phrase) -> {
            if (phrase.other() == null || !phrase.other().contains("{n}") || phrase.neutral() == null) {
                throw new IllegalStateException("Frase do manual incompleta: " + id);
            }
            String format = formats.get(phrase.parameter());
            BigDecimal value = values.get(phrase.parameter());
            if (format == null || value == null) {
                missing.add(phrase.parameter());
                return;
            }
            out.put(id, render(phrase, Format.of(format), value));
            used.put(phrase.parameter(), value);
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Parâmetros do manual sem valor na aplicação: " + missing
                    + " (cada um precisa de um ManualParameterSource no módulo dono)");
        }
        this.rendered = Map.copyOf(out);
        this.valuesFingerprint = fingerprint(used);
    }

    /**
     * 8 hex do SHA-256 dos valores usados nas frases ({@code chave=valor} por linha, em ordem de chave, valor sem zeros
     * à direita): o mesmo JSON do manual com outra configuração gera outro texto, e a versão registrada com cada
     * resposta do chat precisa distinguir os dois.
     */
    public String valuesFingerprint() {
        return valuesFingerprint;
    }

    static String fingerprint(Map<String, BigDecimal> values) {
        StringBuilder canonical = new StringBuilder();
        values.forEach((k, v) -> canonical.append(k).append('=').append(v.stripTrailingZeros().toPlainString()).append('\n'));
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Texto com os marcadores trocados pelo valor; nulo continua nulo. */
    public String resolve(String text) {
        if (text == null || !text.contains("{{")) {
            return text;
        }
        Matcher m = MARKER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = rendered.get(m.group(1));
            if (value == null) {
                throw new IllegalStateException("Marcador desconhecido no manual: " + m.group());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        String result = sb.toString();
        if (result.contains("{{")) {
            throw new IllegalStateException("Marcador mal formado no manual: " + text);
        }
        return result;
    }

    static String render(Phrase phrase, Format format, BigDecimal value) {
        if (format == Format.INTEGER) {
            if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0) {
                throw new IllegalStateException("Parâmetro " + phrase.parameter() + " deve ser inteiro: " + value);
            }
            int n = value.intValueExact();
            if (n == 0 && phrase.zero() != null) {
                return phrase.zero();
            }
            if (n == 1 && phrase.one() != null) {
                return phrase.one();
            }
            return phrase.other().replace("{n}", Integer.toString(n));
        }
        String number = format == Format.MONEY ? "R$ " + grouped(value) : decimal(phrase.parameter(), value);
        return phrase.other().replace("{n}", number);
    }

    /**
     * Decimal com vírgula e as casas que o valor tem, de 2 a 4, <strong>sem arredondar</strong> (0,6 → 0,60;
     * 0,615 → 0,615). Mais de 4 casas significativas impede a subida: o texto nunca mostra número cortado.
     */
    static String decimal(String parameter, BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), DECIMAL_MIN);
        if (scale > DECIMAL_MAX) {
            throw new IllegalStateException("Parâmetro " + parameter + " com mais de " + DECIMAL_MAX + " casas: " + value);
        }
        String plain = stripped.setScale(scale).abs().toPlainString();
        int dot = plain.indexOf('.');
        return (value.signum() < 0 ? "-" : "") + thousands(plain.substring(0, dot)) + "," + plain.substring(dot + 1);
    }

    private static String thousands(String integer) {
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0) {
                grouped.append('.');
            }
            grouped.append(integer.charAt(i));
        }
        return grouped.toString();
    }

    /** Reais: duas casas com vírgula e milhar com ponto (ex.: 1.000,00), sem depender do locale da JVM. */
    static String grouped(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        String plain = scaled.abs().toPlainString();
        String integer = plain.substring(0, plain.indexOf('.'));
        String cents = plain.substring(plain.indexOf('.') + 1);
        return (scaled.signum() < 0 ? "-" : "") + thousands(integer) + "," + cents;
    }
}
