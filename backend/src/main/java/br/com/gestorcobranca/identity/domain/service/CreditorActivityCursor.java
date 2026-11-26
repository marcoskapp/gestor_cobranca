package br.com.gestorcobranca.identity.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Cursor opaco da "Minha atividade" do portal do credor: a posição ({@code occurred_at}, {@code id}) do último item
 * da página, nunca o número sequencial da auditoria (que revelaria o volume de registros da assessoria). Alterar o
 * cursor só muda a página da própria atividade: a consulta continua restrita às pessoas do credor do ator.
 */
public record CreditorActivityCursor(Instant occurredAt, UUID id) {

    private static final String VERSION = "a1";
    private static final int MAX_LENGTH = 120;

    public CreditorActivityCursor {
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    public String encode() {
        String raw = VERSION + "|" + ChronoUnit.MICROS.between(Instant.EPOCH, occurredAt) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** Cursor inválido (formato, versão ou tamanho) → 422 {@code creditor_activity.cursor_invalid}. */
    public static CreditorActivityCursor decode(String cursor) {
        try {
            if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
                throw new IllegalArgumentException();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split("\\|");
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            return new CreditorActivityCursor(Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS),
                    UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
            throw new BusinessRuleException("creditor_activity.cursor_invalid",
                    "A página pedida não é válida. Volte ao início da lista.");
        }
    }
}
