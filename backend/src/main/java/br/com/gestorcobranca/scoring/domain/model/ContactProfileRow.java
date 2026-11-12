package br.com.gestorcobranca.scoring.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Perfil agregado de um contato num credor e canal ({@code scoring.contact_profile}), derivado dos fatos vigentes.
 *
 * @param creditorId             credor dos fatos (nulo quando o evento não trouxe credor)
 * @param contactId              contato (nulo = fatos sem contato identificado)
 * @param attempts               tentativas feitas pela assessoria
 * @param successes              tentativas atendidas, efetivas ou respondidas
 * @param debtorInitiated        contatos iniciados pelo devedor
 * @param reads                  mensagens lidas (sinal fraco, só exibido)
 * @param attemptsToFirstSuccess posição (1…) da primeira tentativa com sucesso, ou nulo
 * @param slots                  as {@value ContactSlots#COUNT} faixas horárias de 3 h (06–24), na ordem
 */
public record ContactProfileRow(UUID debtorId, UUID creditorId, UUID contactId, ContactChannel channel, int attempts,
                                int successes, int debtorInitiated, int reads, Integer attemptsToFirstSuccess,
                                Instant lastSuccessAt, Instant lastAttemptAt, List<SlotCount> slots) {

    public ContactProfileRow {
        slots = List.copyOf(slots);
        if (slots.size() != ContactSlots.COUNT) {
            throw new IllegalArgumentException("Perfil com " + slots.size() + " faixas horárias");
        }
    }

    /** Tentativas e sucessos numa faixa horária, com a última tentativa (desempate pela mais recente). */
    public record SlotCount(int attempts, int successes, Instant lastAttemptAt) {

        public static final SlotCount EMPTY = new SlotCount(0, 0, null);

        public SlotCount plus(SlotCount other) {
            return new SlotCount(attempts + other.attempts, successes + other.successes,
                    ContactSlots.latest(lastAttemptAt, other.lastAttemptAt));
        }
    }
}
