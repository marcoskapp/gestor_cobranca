package br.com.gestorcobranca.scoring.domain.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato da inteligência de contato ({@code scoring.contact_attempt}): uma tentativa de contato (ligação feita,
 * mensagem enviada, interação manual de saída), um contato iniciado pelo devedor (ligação recebida, mensagem
 * recebida, interação de entrada) ou a leitura de uma mensagem (sinal fraco, só exibido). Sem dado pessoal: o
 * contato é referenciado pelo id (o valor fica no {@code debt}).
 *
 * @param contactId    contato do devedor usado (nulo quando o evento não identificou o contato)
 * @param success      tentativa atendida/efetiva ou respondida; contato do devedor é sempre sucesso; leitura nunca
 * @param localHour    hora no fuso de operação (0–23)
 * @param localWeekday dia da semana ISO no fuso de operação (1 = segunda … 7 = domingo)
 * @param personType   INDIVIDUAL ou COMPANY (nulo se desconhecido)
 */
public record ContactFact(UUID sourceEventId, Kind kind, UUID debtorId, UUID creditorId, UUID portfolioId, UUID caseId,
                          UUID contactId, ContactChannel channel, Direction direction, boolean success, int localHour,
                          int localWeekday, String personType, Instant occurredAt) {

    public enum Kind {
        /** Tentativa de contato feita pela assessoria. */
        ATTEMPT,
        /** Contato iniciado pelo devedor. */
        RESPONSE,
        /** Mensagem lida (não conta como sucesso). */
        READ
    }

    public enum Direction { OUTBOUND, INBOUND }

    public ContactFact {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (localHour < 0 || localHour > 23 || localWeekday < 1 || localWeekday > 7) {
            throw new IllegalArgumentException("Hora ou dia da semana fora da faixa");
        }
        if (kind == Kind.READ && success) {
            throw new IllegalArgumentException("Leitura de mensagem não é sucesso");
        }
    }

    /** Fato com a hora e o dia da semana do instante no fuso de operação. */
    public static ContactFact of(UUID sourceEventId, Kind kind, UUID debtorId, UUID creditorId, UUID portfolioId,
                                 UUID caseId, UUID contactId, ContactChannel channel, Direction direction,
                                 boolean success, String personType, Instant occurredAt, ZoneId zone) {
        ZonedDateTime local = occurredAt.atZone(zone);
        return new ContactFact(sourceEventId, kind, debtorId, creditorId, portfolioId, caseId, contactId, channel,
                direction, success, local.getHour(), local.getDayOfWeek().getValue(), personType, occurredAt);
    }
}
