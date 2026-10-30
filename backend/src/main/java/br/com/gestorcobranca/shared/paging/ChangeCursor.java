package br.com.gestorcobranca.shared.paging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Posição numa leitura incremental por chave ({@code updated_at}, {@code id}) — paginação por cursor (keyset) para
 * sincronização: o próximo lote começa estritamente depois deste par, na ordem crescente de atualização e id.
 * <p>
 * O formato textual ({@link #encode()}) é opaco para o cliente: Base64 URL-safe de {@code v1:<segundos>:<nanos>:<id>}.
 */
public record ChangeCursor(Instant updatedAt, UUID id) {

    private static final Pattern RAW = Pattern.compile("v1:(-?\\d{1,19}):(\\d{1,9}):([0-9a-f-]{36})");

    public ChangeCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(id, "id");
    }

    public String encode() {
        String raw = "v1:%d:%d:%s".formatted(updatedAt.getEpochSecond(), updatedAt.getNano(), id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws IllegalArgumentException cursor malformado (o chamador traduz para erro de requisição) */
    public static ChangeCursor decode(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw new IllegalArgumentException("Cursor inválido");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token.strip()), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cursor inválido", e);
        }
        var m = RAW.matcher(raw);
        if (!m.matches()) {
            throw new IllegalArgumentException("Cursor inválido");
        }
        try {
            return new ChangeCursor(Instant.ofEpochSecond(Long.parseLong(m.group(1)), Long.parseLong(m.group(2))),
                    UUID.fromString(m.group(3)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cursor inválido", e);
        }
    }
}
