package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
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

    /** Bloqueia o pagamento (alocação, estorno e conciliação são serializados por pagamento). */
    Optional<Payment> findByIdForUpdate(UUID id);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    List<Payment> findByInstructionIdOrderByCreatedAtAsc(UUID instructionId);

    List<Payment> findByExternalReferenceAndStatus(String externalReference, PaymentStatus status);

    List<Payment> findAllByIdIn(Collection<UUID> ids);

    Page<Payment> search(Criteria criteria, Pageable pageable);

    /**
     * Pagamentos confirmados/conciliados com saldo não alocado ou com valor não aplicado pelo
     * negotiation ainda não realocado, do mais antigo ao mais recente.
     *
     * @param creditorIds credores permitidos (nulo = todos)
     */
    Page<UUID> findPendingReallocationIds(Set<UUID> creditorIds, Pageable pageable);

    /**
     * @param creditorIds credores permitidos (nulo = todos)
     * @param statuses    situações (vazio = todas)
     * @param withBalance somente com saldo não alocado
     * @param excludeInformedBy exclui os informados por este usuário (fila de revisão)
     */
    record Criteria(Set<UUID> creditorIds, UUID creditorId, UUID debtorId, Set<PaymentStatus> statuses, String source,
                    String method, Instant paidFrom, Instant paidTo, boolean withBalance, UUID excludeInformedBy) {
    }
}
