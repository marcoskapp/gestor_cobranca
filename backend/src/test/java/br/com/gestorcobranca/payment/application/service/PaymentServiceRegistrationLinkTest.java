package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorCreditorLinkQueries;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases;
import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.payment.application.port.out.ExternalPaymentLocks;
import br.com.gestorcobranca.payment.application.port.spi.IntegrationTokenLabels;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H07b-07 / RS-124: o registro externo confere a ligação do devedor com o credor por consulta escalar <b>antes</b> de
 * qualquer trava; sem ligação, nenhuma trava é tomada e a recusa é a mesma do destino que não pertence ao par.
 */
class PaymentServiceRegistrationLinkTest {

    private final ExternalPaymentLocks locks = mock(ExternalPaymentLocks.class);
    private final DebtorCreditorLinkQueries holds = mock(DebtorCreditorLinkQueries.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final TargetResolver resolver = mock(TargetResolver.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);

    private final PaymentService service = new PaymentService(payments, mock(PaymentAllocationRepository.class),
            mock(PaymentReversalRepository.class), resolver, mock(AllocationService.class),
            mock(PaymentConfirmation.class), mock(PaymentReversals.class), mock(PaymentRecorder.class),
            mock(EvidenceStore.class), mock(PaymentSettingsProvider.class), idempotency, mock(FileStorage.class),
            mock(AccessScope.class), currentActor, mock(AccessLogger.class), mock(PaymentMetrics.class),
            mock(JsonCodec.class), Clock.systemUTC(), locks, new PaymentProperties(null, null, null, null, null, null,
            null, null, null), mock(AgreementBreakSuspensionUseCases.class), mock(StatementLineReview.class),
            mock(PaymentReferenceDigest.class), mock(IntegrationTokenLabels.class), holds);

    @Test
    void withoutLinkNoLockIsTakenAndTheRefusalIsTheTargetMismatch() {
        UUID creditor = UUID.randomUUID();
        UUID debtor = UUID.randomUUID();
        when(currentActor.get()).thenReturn(new Actor(Actor.ActorType.INTEGRATION, UUID.randomUUID(), UUID.randomUUID(),
                null, "token", Set.of(Permission.PAYMENT_REGISTER_EXTERNAL), CreditorScope.NONE));
        when(holds.linked(creditor, debtor)).thenReturn(false);
        ExternalPaymentCommand command = new ExternalPaymentCommand(creditor, debtor, new BigDecimal("10.00"),
                Instant.now(), "PIX", "CREDITOR_DIRECT", null, null,
                new TargetInput("TITLES", null, List.of(UUID.randomUUID()), null));

        assertThatThrownBy(() -> service.registerExternal(command, null, "k"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("allocation.target_mismatch");
        verifyNoInteractions(locks, resolver, idempotency);
    }
}
