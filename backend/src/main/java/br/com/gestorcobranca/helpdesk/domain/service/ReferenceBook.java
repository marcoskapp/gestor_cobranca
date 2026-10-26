package br.com.gestorcobranca.helpdesk.domain.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Referências opacas de uma conversa ({@code D1} = primeiro devedor citado, {@code A2} = segundo acordo…): a IA vê
 * e devolve só a referência; o servidor guarda o id interno e confere o tipo a cada uso. Assim o modelo nunca recebe
 * ids, nomes ou documentos, e não consegue apontar para um registro que a conversa não mostrou.
 */
public final class ReferenceBook {

    public static final int MAX_REFERENCES = 300;
    private static final Pattern REF = Pattern.compile("([A-Z])(\\d{1,3})");

    /** Tipo de registro e o prefixo da referência. */
    public enum Kind {
        DEBTOR("D"), CASE("C"), AGREEMENT("A"), PAYMENT("P"), IMPORT("L"), SETTLEMENT_STATEMENT("S"),
        COMMISSION_STATEMENT("E");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    /** referência → {@code KIND:uuid} */
    private final Map<String, String> entries;

    private ReferenceBook(Map<String, String> entries) {
        this.entries = new LinkedHashMap<>(entries);
    }

    public static ReferenceBook empty() {
        return new ReferenceBook(Map.of());
    }

    /** Reconstrói a partir do que foi gravado; entradas malformadas são ignoradas. */
    public static ReferenceBook of(Map<String, ?> stored) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (stored != null) {
            stored.forEach((ref, value) -> {
                if (ref != null && REF.matcher(ref).matches() && value instanceof String s && parse(s) != null) {
                    clean.put(ref, s);
                }
            });
        }
        return new ReferenceBook(clean);
    }

    /** Referência do registro (a mesma se já citado nesta conversa). */
    public String refFor(Kind kind, UUID id) {
        String value = kind.name() + ":" + id;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getValue().equals(value)) {
                return e.getKey();
            }
        }
        if (entries.size() >= MAX_REFERENCES) {
            throw new IllegalStateException("Conversa com referências demais");
        }
        int next = 1 + (int) entries.keySet().stream().filter(k -> k.startsWith(kind.prefix())).count();
        String ref = kind.prefix() + next;
        entries.put(ref, value);
        return ref;
    }

    /** Id do registro se a referência existe nesta conversa e é do tipo esperado. */
    public Optional<UUID> resolve(Kind kind, String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        String value = entries.get(ref.trim().toUpperCase(java.util.Locale.ROOT));
        if (value == null) {
            return Optional.empty();
        }
        String[] parsed = parse(value);
        return parsed != null && parsed[0].equals(kind.name()) ? Optional.of(UUID.fromString(parsed[1])) : Optional.empty();
    }

    public Map<String, String> toMap() {
        return Map.copyOf(entries);
    }

    private static String[] parse(String value) {
        int sep = value.indexOf(':');
        if (sep < 0) {
            return null;
        }
        String kind = value.substring(0, sep);
        try {
            Kind.valueOf(kind);
            UUID.fromString(value.substring(sep + 1));
            return new String[] {kind, value.substring(sep + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
