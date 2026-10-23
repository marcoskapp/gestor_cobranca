package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PaymentInstructionRepository {

    PaymentInstruction save(PaymentInstruction instruction);

    Optional<PaymentInstruction> findById(UUID id);

    Optional<PaymentInstruction> findByIdForUpdate(UUID id);

    Optional<PaymentInstruction> findByTxid(String txid);

    /** Bloqueia a instrução do {@code txid} (pagamentos da mesma instrução são serializados). */
    Optional<PaymentInstruction> findByTxidForUpdate(String txid);

    List<PaymentInstruction> findByAgreementIdAndStatusIn(UUID agreementId, Collection<InstructionStatus> statuses);

    List<PaymentInstruction> findByInstallmentIdAndStatusIn(UUID installmentId, Collection<InstructionStatus> statuses);

    List<PaymentInstruction> findByTitleIdAndStatusIn(UUID titleId, Collection<InstructionStatus> statuses);

    /** Abertas com expiração até o instante (job de expiração). */
    List<PaymentInstruction> findOpenExpiredBefore(Instant now, int limit);

    Page<PaymentInstruction> search(Criteria criteria, Pageable pageable);

    /** @param creditorIds credores permitidos (nulo = todos) */
    record Criteria(Set<UUID> creditorIds, UUID creditorId, UUID debtorId, UUID agreementId, UUID titleId,
                    Set<InstructionStatus> statuses) {
    }
}
