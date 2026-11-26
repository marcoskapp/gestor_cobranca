package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.debt.domain.repository.ReversalHoldRepository;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * RS-108 (b): a retenção é gravada na mesma transação do estorno, com falha fechada — se a gravação falha, o estorno
 * inteiro é desfeito: pagamento continua confirmado, a baixa no título continua, nenhum {@code PaymentReversed} no outbox.
 */
class ReversalHoldFailClosedIT extends PaymentTestSupport {

    @MockitoSpyBean
    ReversalHoldRepository holdRepository;

    @Test
    void failureToPlaceTheHoldUndoesTheWholeReversal() {
        provision("retencao-falha");
        PaymentView paid = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal("400.00"), Instant.now().minus(1, ChronoUnit.HOURS), "PIX", "CREDITOR_DIRECT", null,
                null, new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null));
        assertThat(paid.status()).isEqualTo("CONFIRMED");
        BigDecimal balanceBefore = system(() -> debtTitles.get(titleB)).openBalance();
        assertThat(balanceBefore).isEqualByComparingTo("600.00");
        long publishedBefore = reversedPublications();
        doThrow(new DataIntegrityViolationException("falha simulada")).when(holdRepository).saveAndFlush(any());
        try {
            assertThatThrownBy(() -> asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe"))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            reset(holdRepository);
        }
        assertThat(asFinance1(() -> payments.get(paid.id())).status()).isEqualTo("CONFIRMED");
        assertThat(system(() -> debtTitles.get(titleB)).openBalance()).isEqualByComparingTo(balanceBefore);
        assertThat(count("select count(*) from payment.payment_reversal where payment_id = ?", paid.id())).isZero();
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ?", paid.id())).isZero();
        assertThat(reversedPublications()).isEqualTo(publishedBefore);

        // sem a falha, o mesmo estorno passa e cria a retenção
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ? and state = 'PENDING'",
                paid.id())).isEqualTo(1);
        assertThat(reversedPublications()).isGreaterThan(publishedBefore);
    }

    private long reversedPublications() {
        Long value = jdbcTemplate.queryForObject("""
                select count(*) from public.event_publication
                 where event_type like '%.PaymentReversed' and serialized_event like ?""", Long.class,
                "%" + tenant + "%");
        return value == null ? 0 : value;
    }
}
