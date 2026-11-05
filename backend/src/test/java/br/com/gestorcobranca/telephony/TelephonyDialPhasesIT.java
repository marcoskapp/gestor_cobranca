package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallFilter;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.EntryView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.AccountView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases.SaveRouteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discagem em fases (TEL-01 e TEL-02): o pedido é confirmado antes do HTTP ao provedor, o HTTP corre sem
 * transação e o resultado é gravado depois. No sandbox, destinos terminados em {@code 7777} entregam o toque
 * e o atendimento (como webhooks) antes da resposta ao pedido; em {@code 9999}, falha transitória do lote.
 */
class TelephonyDialPhasesIT extends TelephonyTestSupport {

    @Autowired
    TelephonyRoutingUseCases routing;

    UUID queue;

    @BeforeEach
    void setUp() {
        provision("fas", false);
        queue = queueA("FASES");
    }

    @Test
    void clickToCallEventsThatArriveBeforeTheDialResponseAreApplied() {
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25", "(11) 98765-7777"), "800.00");
        asOperator(operatorX, () -> agents.login(null));
        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        assertThat(queued.status()).isEqualTo("QUEUED");
        drainTelephony();

        // os eventos acharam a chamada já em DIALING (pela referência) e a avançaram; o aceite veio depois
        CallView answered = call(queued.id());
        assertThat(answered.status()).isEqualTo("ANSWERED");
        assertThat(answered.ringingAt()).isNotNull();
        assertThat(answered.connectedAt()).isNotNull();
        assertThat(answered.operatorId()).isEqualTo(operatorX);
        assertThat(answered.dialStartedAt()).isNotNull();
        assertThat(string("select provider_call_id from telephony.phone_call where id = ?", queued.id()))
                .startsWith("sbx-call-");
        assertThat(count("select count(*) from telephony.provider_event where provider_event_id like 'sbx-fast-%'")).isEqualTo(2);
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ? and origin = 'OPERATOR'",
                queued.id())).isEqualTo(1);
        assertThat(count("select count(*) from telephony.phone_call where id = ? and contact_attempt_id = "
                + "(select id from compliance.contact_attempt where reference_id = ?)", queued.id(), queued.id()))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.dialed' and reference_id = ?",
                queued.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.dial' and status = 'DONE' "
                + "and payload ->> 'callId' = ?", queued.id().toString())).isEqualTo(1);

        assertThat(event(queued.id(), "COMPLETED", null, "DEBTOR")).isEqualTo("applied");
        assertThat(call(queued.id()).result()).isEqualTo("ANSWERED");
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("WRAP_UP");
    }

    @Test
    void dialerCallAnsweredBeforeTheBatchResponseIsBridgedToTheFreeOperator() {
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25", "(11) 98765-7777"), "900.00");
        CampaignView campaign = campaign("RAPIDO");
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();

        CallView connected = dialerCalls(campaign.id()).getFirst();
        assertThat(connected.caseId()).isEqualTo(kase.id());
        assertThat(connected.status()).isEqualTo("ANSWERED");
        assertThat(connected.operatorId()).isEqualTo(operatorX);
        assertThat(connected.connectedAt()).isNotNull();
        assertThat(connected.abandoned()).isFalse();
        assertThat(string("select provider_call_id from telephony.phone_call where id = ?", connected.id()))
                .startsWith("sbx-call-");
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("ON_CALL");
        assertThat(entries(campaign.id())).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("IN_PROGRESS");
            assertThat(e.attempts()).isEqualTo(1);
        });
    }

    @Test
    void transientFailureOfOneBatchDoesNotUndoTheBatchesAlreadyAccepted() {
        AccountView other = asAdmin(() -> accounts.create(new CreateAccountCommand("SANDBOX", null, "Sandbox 2",
                "+551130000001", false, false, Map.of("webhookSecret", "outro-segredo"))));
        UUID portfolioA2 = portfolio(creditorA, "A-02");
        asManager(() -> routing.save(new SaveRouteCommand("PORTFOLIO", portfolioA2, other.id(), null)));
        CaseView reachable = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        CaseView unavailable = loadCase(creditorA, portfolioA2, debtor("111.444.777-35", "(11) 97777-9999"), "300.00");
        CampaignView campaign = campaign("LOTES");
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        asOperator(operatorY, () -> agents.login(campaign.id()));
        drainTelephony();

        List<CallView> placed = dialerCalls(campaign.id());
        assertThat(placed).hasSize(2);
        CallView accepted = placed.stream().filter(c -> c.caseId().equals(reachable.id())).findFirst().orElseThrow();
        CallView refused = placed.stream().filter(c -> c.caseId().equals(unavailable.id())).findFirst().orElseThrow();
        assertThat(accepted.status()).isEqualTo("DIALING");
        assertThat(accepted.accountId()).isEqualTo(account.id());
        assertThat(string("select provider_call_id from telephony.phone_call where id = ?", accepted.id()))
                .startsWith("sbx-call-");
        assertThat(refused.status()).isEqualTo("FAILED");
        assertThat(refused.accountId()).isEqualTo(other.id());
        assertThat(refused.failureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(refused.dialStartedAt()).isNull();
        assertThat(refused.interactionState()).isEqualTo("SKIPPED");
        // as duas tentativas ficam no compliance: a do lote aceito e a do lote não discado (pelo lado seguro)
        assertThat(count("select count(*) from compliance.contact_attempt where origin = 'CAMPAIGN' and reference_id in (?, ?)",
                accepted.id(), refused.id())).isEqualTo(2);
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.dialer_tick' "
                + "and status = 'DONE' and tenant_id = ?", tenant)).isPositive();
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.dialer_tick' "
                + "and status in ('DEAD', 'RUNNING') and tenant_id = ?", tenant)).isZero();

        List<EntryView> list = entries(campaign.id());
        assertThat(list).filteredOn(e -> e.caseId().equals(reachable.id())).singleElement()
                .satisfies(e -> assertThat(e.status()).isEqualTo("IN_PROGRESS"));
        assertThat(list).filteredOn(e -> e.caseId().equals(unavailable.id())).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("PENDING");
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.nextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
        });
    }

    private CampaignView campaign(String code) {
        return asManager(() -> dialer.create(new CreateCampaignCommand(code, null, queue, new CampaignSettings(
                "Campanha " + code, null, false, "PROGRESSIVE", null, null, 3, 30, 20, null, null))));
    }

    private List<CallView> dialerCalls(UUID campaignId) {
        return asSupervisor(() -> calls.search(new CallFilter(null, null, null, null, campaignId, null, null, null, null,
                null), PageQuery.of(0, 50))).content();
    }

    private List<EntryView> entries(UUID campaignId) {
        return asSupervisor(() -> dialer.entries(campaignId, null, PageQuery.of(0, 50))).content();
    }
}
