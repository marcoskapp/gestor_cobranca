package br.com.gestorcobranca.helpdesk.domain.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máscara determinística de dados pessoais em texto livre do chat (independe da IA):
 * <ul>
 *   <li>{@link #mask(String)} — documento (CPF/CNPJ, com ou sem pontuação), e-mail, telefone e CEP viram marcadores
 *       ({@code [documento]}, {@code [e-mail]}, {@code [telefone]}, {@code [cep]}); usado nas respostas do chat;</li>
 *   <li>{@link #withPlaceholders(List)} — para o texto que vai ao modelo: cada documento vira {@code [DOC1]},
 *       {@code [DOC2]}… (numeração estável pela ordem em que aparecem na conversa) e o servidor guarda o valor para a
 *       busca de devedor; e-mail, telefone e CEP são mascarados.</li>
 * </ul>
 */
public final class PersonalData {

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** CPF (11) ou CNPJ (14 dígitos), com ou sem pontuação, sem dígitos colados. */
    static final Pattern DOCUMENT = Pattern.compile(
            "(?<![\\d.\\-/])(\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}|\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2})(?![\\d\\-/]|\\.\\d)");
    static final Pattern PHONE = Pattern.compile(
            "(?<![\\d\\w])(\\+?55\\s?)?(\\(?\\d{2}\\)?\\s?)?9?\\d{4}[\\s.-]?\\d{4}(?![\\d\\w])");
    static final Pattern CEP = Pattern.compile("(?<!\\d)\\d{5}-\\d{3}(?!\\d)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[DOC(\\d{1,3})]");

    private PersonalData() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String r = EMAIL.matcher(text).replaceAll("[e-mail]");
        r = DOCUMENT.matcher(r).replaceAll("[documento]");
        r = CEP.matcher(r).replaceAll("[cep]");
        return PHONE.matcher(r).replaceAll("[telefone]");
    }

    /**
     * @param texts textos da conversa em ordem
     * @return textos com documentos trocados por {@code [DOCn]} e o mapa {@code DOCn → dígitos}
     */
    public static Placeholders withPlaceholders(List<String> texts) {
        Map<String, String> byDigits = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (String text : texts) {
            if (text == null) {
                out.add(null);
                continue;
            }
            String r = EMAIL.matcher(text).replaceAll("[e-mail]");
            Matcher m = DOCUMENT.matcher(r);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String digits = m.group().replaceAll("\\D", "");
                String key = byDigits.computeIfAbsent(digits, d -> "DOC" + (byDigits.size() + 1));
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + key + "]"));
            }
            m.appendTail(sb);
            r = CEP.matcher(sb.toString()).replaceAll("[cep]");
            out.add(PHONE.matcher(r).replaceAll("[telefone]"));
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        byDigits.forEach((digits, key) -> byKey.put(key, digits));
        return new Placeholders(out, byKey);
    }

    /**
     * @param texts      textos mascarados, na ordem recebida
     * @param documents  {@code DOCn} → dígitos do documento
     */
    public record Placeholders(List<String> texts, Map<String, String> documents) {

        /** Documento por trás de {@code [DOCn]} ou {@code DOCn}; nulo se não for um marcador desta conversa. */
        public String resolve(String value) {
            if (value == null) {
                return null;
            }
            String v = value.trim();
            Matcher m = PLACEHOLDER.matcher(v.startsWith("[") ? v : "[" + v + "]");
            return m.matches() ? documents.get("DOC" + m.group(1)) : null;
        }
    }
}
