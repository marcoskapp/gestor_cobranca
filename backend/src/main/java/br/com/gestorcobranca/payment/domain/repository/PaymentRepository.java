package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Payment saveAndFlush(Payment payment);

    Optional<Payment> findById(UUID id);

    /** Informes do portal do credor ainda em análise (limite por credor, RS-36; índice {@code ix_payment_creditor_portal}). */
    long countCreditorPortalInReview(UUID creditorId);

    /** Informe do portal em análise com os mesmos títulos, valor e data (duplicidade entre pessoas do credor). */
    boolean existsCreditorPortalDuplicate(UUID creditorId, String titleNumbers, Money amount, Instant paidAt);

    /** Informes do portal do credor, do mais recente ao mais antigo. */
    List<Payment> findCreditorPortalReports(UUID creditorId, int limit);

    /** Informes do portal do credor recusados com a decisão a partir de {@code since}, do mais recente ao mais antigo. */
    List<Payment> findCreditorPortalRejectedSince(UUID creditorId, java.time.Instant since, int limit);

    /** Bloqueia o pagamento (alocação, estorno e conciliação são serializados por pagamento). */
    Optional<Payment> findByIdForUpdate(UUID id);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    /** Bloqueia o pagamento da transação do provedor (antes de carregá-lo, evitando versão desatualizada). */
    Optional<Payment> findByProviderTransactionIdForUpdate(String providerTransactionId);

    /** Instrução citada pelo pagamento criado por linha de extrato (sem carregar o pagamento); vazio nos demais. */
    Optional<UUID> findStatementReviewInstructionId(UUID paymentId);

    List<Payment> findByInstructionIdOrderByCreatedAtAsc(UUID instructionId);

    List<Payment> findByExternalReferenceAndStatus(String externalReference, PaymentStatus status);

    List<Payment> findAllByIdIn(Collection<UUID> ids);

    /** Grava as alterações pendentes no banco (antes de efeitos fora da transação). */
    void flush();

    /** Todos os pagamentos do devedor (anonimização do titular). */
    List<Payment> findByDebtorId(UUID debtorId);

    Page<Payment> search(Criteria criteria, Pageable pageable);

    /** Leitura incremental: até {@code limit + 1} pagamentos do credor alterados na janela, depois do cursor. */
    List<Payment> findChanges(UUID creditorId, ChangeQuery query);

    /** Soma dos pagamentos externos confirmados sem revisão, do devedor no credor, informados a partir de {@code since}. */
    Money sumExternalInformedForDebtor(UUID creditorId, UUID debtorId, Instant since);

    /** Soma dos pagamentos externos confirmados sem revisão, informados pelo usuário a partir de {@code since}. */
    Money sumExternalInformedBy(UUID informedBy, Instant since);

    /** Soma confirmada sem revisão pela integração do credor (todos os tokens) desde {@code since} (teto por credor). */
    Money sumIntegrationInformedForCreditor(UUID creditorId, Instant since);

    /**
     * Pagamentos confirmados/conciliados com saldo não alocado ou com valor não aplicado pelo
     * negotiation ainda não realocado, do mais antigo ao mais recente.
     *
     * @param creditorIds credores permitidos (nulo = todos)
     */
    Page<UUID> findPendingReallocationIds(Set<UUID> creditorIds, Pageable pageable);

    /**
     * Pagamentos em revisão (externos e créditos de extrato — decisão 1451) destinados aos acordos aguardando revisão ({@code INFORMED}) ou recusados
     * ({@code REJECTED}) a partir de {@code rejectedSince}, por data do registro.
     *
     * @param creditorIds credores permitidos (nulo = todos)
     */
    List<Payment> findReviewStatusByAgreements(Set<UUID> creditorIds, Collection<UUID> agreementIds,
                                               Instant rejectedSince);

    /** Como {@link #findReviewStatusByAgreements}, para o devedor no credor (acordos e títulos avulsos). */
    List<Payment> findReviewStatusByDebtor(UUID creditorId, UUID debtorId, Instant rejectedSince);

    /** Externos com o acordo como destino aguardando revisão ({@code INFORMED}), por data do registro (sistema). */
    List<Payment> findPendingReviewByAgreement(UUID agreementId);

    /** Externos com o acordo como destino revisados por segunda pessoa a partir de {@code since} (sistema). */
    List<Payment> findReviewedByAgreementSince(UUID agreementId, Instant since);

    /**
     * @param creditorIds credores permitidos (nulo = todos)
     * @param statuses    situações (vazio = todas)
     * @param withBalance somente com saldo não alocado
     * @param excludeInformedBy exclui os informados por este usuário (fila de revisão)
     * @param agreementId       destinados ao acordo (destino solicitado do externo ou alguma alocação a ele)
     * @param externalOrigin    fonte do pagamento externo (AGENCY_ACCOUNT, CREDITOR_DIRECT, CREDITOR_REPORT, OTHER)
     */
    record Criteria(Set<UUID> creditorIds, UUID creditorId, UUID debtorId, Set<PaymentStatus> statuses, String source,
                    String method, Instant paidFrom, Instant paidTo, boolean withBalance, UUID excludeInformedBy,
                    UUID agreementId, String externalOrigin) {
    }
}
