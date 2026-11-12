package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.CostingProperties;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore.RefreshState;
import br.com.gestorcobranca.costing.application.port.out.UsageReader;
import br.com.gestorcobranca.costing.domain.repository.ChannelInvoiceRepository;
import br.com.gestorcobranca.costing.domain.repository.ChannelTariffRepository;
import br.com.gestorcobranca.costing.domain.repository.CostBandRepository;
import br.com.gestorcobranca.costing.domain.repository.FxRateRepository;
import br.com.gestorcobranca.costing.domain.repository.OverheadRepository;
import br.com.gestorcobranca.costing.domain.repository.UserCostBandRepository;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regra de apuração nova (decisão 1328): o job refaz uma vez a janela da tendência de 6 meses quando o estado guarda
 * uma versão anterior; e uma reapuração curta não marca o histórico como atualizado.
 */
class CostRefreshRulesUpgradeTest {

    /** 13/11/2026 ao meio-dia em Brasília: ontem = 12/11; a janela da tendência começa em 01/06. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T15:00:00Z"), ZoneOffset.UTC);
    static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 12);
    static final LocalDate TREND_START = LocalDate.of(2026, 6, 1);

    UsageReader usage;
    CostFactStore store;
    CurrentActor currentActor;
    CostRefreshService service;

    @BeforeEach
    void setUp() {
        usage = mock(UsageReader.class);
        store = mock(CostFactStore.class);
        currentActor = mock(CurrentActor.class);
        when(store.tryLockForRefresh(any())).thenReturn(true);
        service = new CostRefreshService(usage, store, mock(CostBandRepository.class),
                mock(UserCostBandRepository.class), mock(ChannelTariffRepository.class), mock(FxRateRepository.class),
                mock(OverheadRepository.class), mock(ChannelInvoiceRepository.class), currentActor,
                mock(AccessScope.class), mock(CostingMetrics.class), new CostingProperties(null, null, null, null, null),
                mock(AuditTrail.class), CLOCK);
    }

    private void stateVersion(int version) {
        when(store.state()).thenReturn(Optional.of(new RefreshState(YESTERDAY.minusDays(1), YESTERDAY.minusDays(35),
                Instant.now(), version)));
    }

    @Test
    void versaoAnteriorRefazAJanelaDaTendenciaUmaVezEGravaAVersaoNova() {
        stateVersion(CostRefreshService.RULES_VERSION - 1);
        service.refreshScheduled();
        verify(usage).workSpans(eq(TREND_START), eq(YESTERDAY), any());
        verify(store).saveState(eq(TREND_START), eq(YESTERDAY), any(), anyLong(), anyLong(),
                eq(CostRefreshService.RULES_VERSION));
    }

    @Test
    void versaoAtualReprocessaSoOsUltimos35Dias() {
        stateVersion(CostRefreshService.RULES_VERSION);
        service.refreshScheduled();
        verify(usage).workSpans(eq(YESTERDAY.minusDays(34)), eq(YESTERDAY), any());
    }

    @Test
    void reapuracaoCurtaNaoMarcaOHistoricoComoAtualizado() {
        stateVersion(CostRefreshService.RULES_VERSION - 1);
        when(currentActor.get()).thenReturn(new Actor(Actor.ActorType.USER, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TEST", EnumSet.of(Permission.COST_MANAGE), CreditorScope.ALL));
        service.refresh(YESTERDAY.minusDays(9), YESTERDAY);
        verify(store).saveState(eq(YESTERDAY.minusDays(9)), eq(YESTERDAY), any(), anyLong(), anyLong(),
                eq(CostRefreshService.RULES_VERSION - 1));
    }
}
