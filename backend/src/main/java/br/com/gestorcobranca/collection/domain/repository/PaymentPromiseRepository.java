package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.model.PromiseStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPromiseRepository {

    PaymentPromise save(PaymentPromise promise);

    /** Grava imediatamente (a promessa substituída precisa sair de PENDING antes da nova entrar). */
    PaymentPromise saveAndFlush(PaymentPromise promise);

    Optional<PaymentPromise> findById(UUID id);

    List<PaymentPromise> findByCaseIdOrderByPromisedAtDesc(UUID caseId);

    List<PaymentPromise> findByCaseIdAndStatus(UUID caseId, PromiseStatus status);

    /** Promessas do devedor no credor nos status informados (apuração de pagamentos). */
    List<PaymentPromise> findByDebtorIdAndCreditorIdAndStatusIn(UUID debtorId, UUID creditorId,
                                                                Collection<PromiseStatus> statuses);

    /** Promessas que consideraram o pagamento (estorno). */
    List<PaymentPromise> findByPaymentId(UUID paymentId);

    /** Pendentes cujo prazo de tolerância terminou antes de {@code today}. */
    List<PaymentPromise> findPendingBrokenBy(LocalDate today, int limit);
}
