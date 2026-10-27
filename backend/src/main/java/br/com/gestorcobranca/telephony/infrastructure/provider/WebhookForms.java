package br.com.gestorcobranca.telephony.infrastructure.provider;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Utilitários dos webhooks de CPaaS com corpo {@code application/x-www-form-urlencoded} (Twilio,
 * Plivo): leitura do formulário, URLs de callback por chamada (só referências opacas: tipo do
 * evento, id interno da chamada, perna e operador — nunca número ou nome) e escape de XML das
 * instruções (TwiML/Plivo XML).
 */
public final class WebhookForms {

    /** Parâmetros das URLs de callback geradas pelos adaptadores. */
    public static final String KIND = "k";
    public static final String REFERENCE = "ref";
    public static final String LEG = "leg";
    public static final String AMD = "amd";
    public static final String OPERATOR = "op";

    private static final int MAX_FIELDS = 200;

    private WebhookForms() {
    }

    /**
     * Formulário decodificado, na ordem recebida; nomes repetidos acumulam valores.
     *
     * @throws IllegalArgumentException codificação inválida ou campos demais
     */
    public static Map<String, List<String>> parse(byte[] body) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return values;
        }
        int count = 0;
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (++count > MAX_FIELDS) {
                throw new IllegalArgumentException("Formulário com campos demais");
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return values;
    }

    public static String first(Map<String, List<String>> form, String name) {
        List<String> values = form.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** UUID da consulta, ou nulo se ausente/inválido. */
    public static UUID uuid(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * URL de callback: endereço do webhook da conta + consulta (valores opacos e curtos).
     *
     * @throws IllegalStateException conta sem endereço de webhook
     */
    public static String callback(String webhookUrl, Map<String, String> query) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("Endereço público do webhook da conta indisponível");
        }
        StringBuilder url = new StringBuilder(stripQuery(webhookUrl));
        char separator = '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            url.append(separator).append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return url.toString();
    }

    public static String stripQuery(String url) {
        int q = url == null ? -1 : url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** Escape de texto e atributos XML. */
    public static String xml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
