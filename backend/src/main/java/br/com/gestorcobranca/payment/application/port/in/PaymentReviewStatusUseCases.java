package br.com.gestorcobranca.payment.application.port.in;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases.BreakSuspensionView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Situação de revisão dos pagamentos externos para quem acompanha o acordo ou o caso
 * ({@code AGREEMENT_VIEW}): pagamentos informados aguardando a aprovação de outra pessoa
 * ({@code INFORMED}) e recusados recentemente ({@code REJECTED}), sem a evidência, a observação nem a
 * referência externa — o detalhe completo continua exigindo {@code PAYMENT_VIEW}.
 * <p>
 * O negotiation não depende do payment (grafo do Spring Modulith); por isso o resumo é servido aqui, a
 * partir da fonte (o próprio pagamento), e a tela do acordo o compõe. Escopo por credor em tudo. Traz
 * também a suspensão da quebra (lida do negotiation): enquanto houver pendente, a quebra fica suspensa
 * por até {@code maxDays} dias.
 */
public interface PaymentReviewStatusUseCases {

    /** Máximo de acordos por consulta em lote (uma página da listagem). */
    int MAX_AGREEMENTS = 200;

    /** Situação do acordo, com a previsão das parcelas que os pendentes cobrirão se aprovados. 404 fora do escopo. */
    AgreementReviewStatus forAgreement(UUID agreementId);

    /** Resumo por acordo para listagens (só os acordos com pendente ou recusa recente aparecem). */
    List<AgreementReviewBadge> forAgreements(Collection<UUID> agreementIds);

    /** Situação do devedor no credor (acordos e títulos avulsos), para a ficha do caso. */
    DebtorReviewStatus forDebtor(UUID creditorId, UUID debtorId);

    /**
     * Pagamento externo em revisão ou recusado.
     *
     * @param targetKind        AGREEMENT (parcelas de um acordo) ou TITLES (títulos avulsos)
     * @param requestedItemIds  parcelas ou títulos indicados por quem informou, em ordem; vazio no acordo =
     *                          parcelas em aberto a partir da de menor número
     * @param rejectionReason   motivo da recusa; só para quem informou ou tem {@code PAYMENT_VIEW}
     * @param reasonRestricted  há motivo, mas o perfil de quem consulta não o vê
     */
    record ReviewItem(UUID paymentId, String status, BigDecimal amount, Instant paidAt, String method,
                      UUID informedBy, Instant informedAt, String targetKind, UUID agreementId,
                      List<UUID> requestedItemIds, UUID reviewedBy, Instant reviewedAt, String rejectionReason,
                      boolean reasonRestricted) {

        public ReviewItem {
            requestedItemIds = requestedItemIds == null ? List.of() : List.copyOf(requestedItemIds);
        }
    }

    /** Parte de um pagamento pendente que irá para a parcela se for aprovado agora (previsão, mesma regra da baixa). */
    record CoverageView(UUID installmentId, UUID paymentId, BigDecimal amount) {
    }

    /**
     * Suspensão da quebra por pagamento em revisão (decisões 494–503), lida do negotiation.
     *
     * @param maxDays     prazo máximo de suspensão em dias (texto "por no máximo N dias")
     * @param graceDays   carência depois da recusa, em dias
     * @param suspensions suspensão mais recente de cada acordo envolvido (em vigor ou encerrada); vazia se
     *                    nunca houve ou se quem consulta não tem {@code AGREEMENT_VIEW} no credor
     */
    record BreakSuspensionInfo(int maxDays, int graceDays, List<BreakSuspensionView> suspensions) {

        public BreakSuspensionInfo {
            suspensions = suspensions == null ? List.of() : List.copyOf(suspensions);
        }
    }

    /**
     * @param pending          aguardando revisão, do mais antigo ao mais recente
     * @param rejected         recusados desde {@code rejectedSince}, do mais recente ao mais antigo
     * @param expectedCoverage previsão por parcela (vazia se o acordo não estiver em vigor)
     * @param breakSuspension  suspensão da quebra do acordo (no máximo uma na lista)
     */
    record AgreementReviewStatus(UUID agreementId, int pendingCount, BigDecimal pendingTotal, List<ReviewItem> pending,
                                 List<ReviewItem> rejected, Instant rejectedSince, List<CoverageView> expectedCoverage,
                                 BreakSuspensionInfo breakSuspension) {
    }

    record AgreementReviewBadge(UUID agreementId, int pendingCount, BigDecimal pendingTotal, Instant lastInformedAt,
                                int rejectedCount, Instant lastRejectedAt) {
    }

    /** @param breakSuspension suspensões da quebra dos acordos dos pendentes e recusados listados */
    record DebtorReviewStatus(UUID creditorId, UUID debtorId, int pendingCount, BigDecimal pendingTotal,
                              List<ReviewItem> pending, List<ReviewItem> rejected, Instant rejectedSince,
                              BreakSuspensionInfo breakSuspension) {
    }
}
