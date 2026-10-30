package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementPayment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acordos e pagamentos aplicados. */
public interface AgreementRepository {

    Agreement save(Agreement agreement);

    /** Grava e sincroniza com o banco (encerrar o acordo substituído antes de vincular os títulos ao novo). */
    Agreement saveAndFlush(Agreement agreement);

    Optional<Agreement> findById(UUID id);

    /** Bloqueia a linha do acordo (baixas, estornos, quebra e lançamentos são serializados por acordo). */
    Optional<Agreement> findByIdForUpdate(UUID id);

    Optional<Agreement> findByProposalId(UUID proposalId);

    Page<Agreement> search(Collection<UUID> creditorIds, UUID creditorId, UUID debtorId, UUID caseId,
                           AgreementStatus status, Pageable page);

    /** Ids dos acordos em vigor com parcela não paga vencida antes de {@code date}, em ordem de id. */
    List<UUID> findActiveIdsWithInstallmentDueBefore(LocalDate date, UUID afterId, int limit);

    /** Acordos em vigor que cobrem algum dos títulos. */
    List<Agreement> findActiveCovering(Collection<UUID> titleIds);

    List<Agreement> findByDebtorId(UUID debtorId);

    /** Leitura incremental: até {@code limit + 1} acordos do credor alterados na janela, depois do cursor. */
    List<Agreement> findChanges(UUID creditorId, ChangeQuery query);

    AgreementPayment save(AgreementPayment payment);

    Optional<AgreementPayment> findPayment(UUID agreementId, UUID paymentId);

    List<AgreementPayment> findPaymentsByPaymentId(UUID paymentId);

    List<AgreementPayment> findPayments(UUID agreementId);
}
