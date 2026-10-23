package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallFilter;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.CreateAccountCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escopo por credor (404 em leitura, 403 em comando, com registro no log de acesso), restrição de
 * listagem a quem não supervisiona e isolamento entre assessorias por RLS.
 */
class TelephonyScopeIT extends TelephonyTestSupport {

    CaseView caseA;
    CaseView caseB;
    CallView callA;

    @BeforeEach
    void setUp() {
        provision("tsc", false);
        caseA = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "500.00");
        caseB = loadCase(creditorB, portfolioB, debtor("111.444.777-35", "(11) 97777-1234"), "300.00");
        asOperator(operatorX, () -> agents.login(null));
        callA = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(caseA.id(), null), null));
        drainTelephony();
    }

    @Test
    void creditorScopeOnCallsCampaignsAndAccounts() {
        asOperator(operatorZ, () -> agents.login(null));
        assertThatThrownBy(() -> asOperator(operatorZ, () -> calls.get(callA.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(operatorZ, () -> calls.clickToCall(new ClickToCallCommand(caseA.id(), null),
                null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(operatorZ, () -> calls.search(new CallFilter(caseA.id(), null, null, null, null,
                null, null, null, null, null), PageQuery.of(0, 20)))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' and actor_id = ?",
                operatorZ)).isGreaterThanOrEqualTo(2);
        // o operador do credor B liga para o caso do credor B
        CallView callB = asOperator(operatorZ, () -> calls.clickToCall(new ClickToCallCommand(caseB.id(), null), null));
        assertThat(callB.creditorId()).isEqualTo(creditorB);

        // sem supervisão, a listagem sem caso traz só as próprias chamadas
        assertThat(asOperator(operatorY, () -> calls.search(null, PageQuery.of(0, 20))).content()).isEmpty();
        assertThat(asOperator(operatorX, () -> calls.search(null, PageQuery.of(0, 20))).content())
                .extracting(CallView::id).containsExactly(callA.id());
        assertThat(asOperator(operatorY, () -> calls.search(new CallFilter(caseA.id(), null, null, null, null, null, null,
                null, null, null), PageQuery.of(0, 20))).content()).extracting(CallView::id).containsExactly(callA.id());
        assertThat(asSupervisor(() -> calls.search(null, PageQuery.of(0, 20))).content()).hasSize(2);
        assertThat(as(supervisor, "SUPERVISOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                java.util.Set.of(creditorB)), () -> calls.search(null, PageQuery.of(0, 20))).content())
                .extracting(CallView::id).containsExactly(callB.id());

        // encerrar a chamada de outro operador: só supervisão
        assertThatThrownBy(() -> asOperator(operatorY, () -> calls.hangup(callA.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asSupervisor(() -> calls.hangup(callA.id())).hangupSide()).isEqualTo("SYSTEM");
        assertThat(count("select count(*) from shared.audit_event where action = 'call.hung_up_by_supervisor'"))
                .isEqualTo(1);

        // campanha do credor A: invisível para quem só tem o credor B; conta padrão exige todos os credores
        UUID queue = queueA("ESCOPO");
        CampaignView campaign = asManager(() -> dialer.create(new CreateCampaignCommand("ESCOPO", null, queue,
                new CampaignSettings("Escopo", null, false, "PROGRESSIVE", null, null, null, null, null, null, null))));
        assertThatThrownBy(() -> as(UUID.randomUUID(), "GESTOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                java.util.Set.of(creditorB)), () -> dialer.get(campaign.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> as(UUID.randomUUID(), "GESTOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                java.util.Set.of(creditorB)), () -> accounts.create(new CreateAccountCommand("SANDBOX", null, "Outra",
                "+551130000001", false, false, Map.of("webhookSecret", "s"))))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(UUID.randomUUID(), "GESTOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                java.util.Set.of(creditorB)), () -> accounts.get(account.id()))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rowLevelSecurityIsolatesTenants() {
        UUID firstTenant = tenant;
        long calls = count("select count(*) from telephony.phone_call");
        assertThat(calls).isEqualTo(1);
        provision("tsc2", false);
        assertThat(tenant).isNotEqualTo(firstTenant);
        for (String table : new String[]{"phone_call", "agent", "presence_interval", "provider_account", "dialer_entry",
                "call_recording", "provider_event"}) {
            long visible = count("select count(*) from telephony." + table + " where tenant_id = ?", firstTenant);
            assertThat(visible).as(table).isZero();
        }
        assertThatThrownBy(() -> asSupervisor(() -> this.calls.get(callA.id()))).isInstanceOf(ResourceNotFoundException.class);
        // sem tenant na transação nenhuma linha é visível
        Long withoutTenant = jdbcTemplate.queryForObject("select count(*) from telephony.phone_call", Long.class);
        assertThat(withoutTenant).isZero();
        // os intervalos de presença são imutáveis
        tenant = firstTenant;
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update telephony.presence_interval set duration_seconds = 0")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

}
