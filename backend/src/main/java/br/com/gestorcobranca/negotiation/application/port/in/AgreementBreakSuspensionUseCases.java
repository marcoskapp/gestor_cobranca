package br.com.gestorcobranca.negotiation.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Suspensão da quebra do acordo enquanto há pagamento externo aguardando revisão (decisão do responsável
 * de 26/10/2026; {@code negociacao-e-alcadas.md} §10.6.1). A suspensão começa e termina no servidor — no
 * job diário de quebra e quando o payment conclui uma revisão —, sempre conferindo a situação dos
 * pagamentos na fonte ({@code PaymentReviewHolds}); nenhum cliente a liga ou desliga diretamente.
 */
public interface AgreementBreakSuspensionUseCases {

    /** Limite de acordos por consulta em lote. */
    int MAX_AGREEMENTS = 200;

    /**
     * Confere a suspensão em vigor do acordo com a situação atual dos pagamentos e a encerra se a revisão
     * terminou (aprovado ou recusado — com carência), se o prazo venceu ou se o acordo foi encerrado.
     * Chamado pelo payment na mesma transação da aprovação/recusa. Idempotente e sem permissão própria:
     * só aplica o que a fonte determina (não começa suspensão nem quebra o acordo).
     */
    void reconcile(UUID agreementId);

    /**
     * Suspensão mais recente de cada acordo informado (até {@link #MAX_AGREEMENTS}), em vigor ou encerrada;
     * acordos sem suspensão ou fora do escopo de credores do ator não vêm. {@code AGREEMENT_VIEW}.
     */
    List<BreakSuspensionView> latest(Collection<UUID> agreementIds);

    /** Parâmetros vigentes (texto dos avisos: "por no máximo N dias"). */
    BreakSuspensionSettings settings();

    /**
     * @param status         ACTIVE ou ENDED
     * @param suspendedUntil último dia da suspensão (prazo máximo, inclusive)
     * @param endReason      APPROVED, REJECTED, DEADLINE ou AGREEMENT_ENDED (nulo em vigor)
     * @param graceUntil     recusado: a quebra volta a valer depois deste dia
     * @param pendingPayments pagamentos em revisão quando a suspensão começou
     */
    record BreakSuspensionView(UUID id, UUID agreementId, String status, LocalDate startedOn, Instant startedAt,
                               LocalDate suspendedUntil, int pendingPayments, BigDecimal pendingAmount,
                               String endReason, Instant endedAt, LocalDate graceUntil) {
    }

    /**
     * @param maxDays   prazo máximo de suspensão, em dias corridos a partir do início
     * @param graceDays carência depois da recusa, em dias corridos (0 = a quebra vale na próxima execução)
     */
    record BreakSuspensionSettings(int maxDays, int graceDays) {
    }
}
