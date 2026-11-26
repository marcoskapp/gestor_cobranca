package br.com.gestorcobranca.creditorportal.application.service;

import br.com.gestorcobranca.analytics.application.port.in.creditor.CreditorIndicatorsQueries;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.Overview;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Só falha técnica de uma fonte vira seção indisponível; erro de programação sobe. */
class CreditorHomeServiceTest {

    private final CreditorIndicatorsQueries indicators = mock(CreditorIndicatorsQueries.class);
    private final CreditorSettlementUseCases settlement = mock(CreditorSettlementUseCases.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CreditorHomeService service = new CreditorHomeService(indicators, settlement,
            mock(CreditorDisputeUseCases.class), mock(CreditorPaymentUseCases.class), mock(CurrentActor.class),
            new CreditorPortalMetrics(meters), Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void queryTimeoutLeavesOnlyThatSourceUnavailable() {
        when(indicators.indicators(anyString())).thenThrow(new QueryTimeoutException("statement timeout"));

        Overview overview = service.overview("THIS_MONTH");

        assertThat(overview.unavailable()).containsExactly("INDICATORS");
        assertThat(overview.recovery()).isNull();
        assertThat(meters.get("creditor_portal.sources.unavailable").tag("source", "indicators").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void programmingErrorIsNotHiddenAsUnavailable() {
        when(indicators.indicators(anyString())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> service.overview("THIS_MONTH")).isInstanceOf(IllegalStateException.class);
    }
}
