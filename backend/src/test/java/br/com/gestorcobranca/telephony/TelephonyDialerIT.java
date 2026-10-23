package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallFilter;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.TabulateCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignStats;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.EntryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Discador: lista a partir da fila do collection, porteiro de contato negando (restrição exclui,
 * janela adia sem consumir tentativa), ritmo progressivo, distribuição ao operador livre,
 * abandono sem operador, secretária eletrônica, retentativas e indicadores.
 */
class TelephonyDialerIT extends TelephonyTestSupport {

    UUID queue;

    @BeforeEach
    void setUp() {
        provision("dlr", false);
        queue = queueA("DISCADOR");
    }

    private CampaignView campaign(String code, String mode) {
        return asManager(() -> dialer.create(new CreateCampaignCommand(code, null, queue, new CampaignSettings(
                "Campanha " + code, null, false, mode, "PREDICTIVE".equals(mode) ? new BigDecimal("3") : null, null, 3,
                30, 20, null, null))));
    }

    private List<CallView> dialerCalls(UUID campaignId) {
        return asSupervisor(() -> calls.search(new CallFilter(null, null, null, null, campaignId, null, null, null, null,
                null), PageQuery.of(0, 50))).content();
    }

    private List<EntryView> entries(UUID campaignId) {
        return asSupervisor(() -> dialer.entries(campaignId, null, PageQuery.of(0, 50))).content();
    }

    @Test
    void dialsFromTheQueueAndDistributesAnsweredCallsToTheFreeOperator() {
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        assertThat(kase.queueId()).isEqualTo(queue);
        CampaignView campaign = campaign("PROG", "PROGRESSIVE");
        assertThat(campaign.creditorId()).isEqualTo(creditorA);
        assertThat(campaign.maxAbandonRate()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> asOperator(operatorX, () -> agents.login(campaign.id())))
                .isInstanceOf(ConflictException.class);
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();

        List<EntryView> list = entries(campaign.id());
        assertThat(list).singleElement().satisfies(e -> {
            assertThat(e.caseId()).isEqualTo(kase.id());
            assertThat(e.status()).isEqualTo("IN_PROGRESS");
            assertThat(e.attempts()).isEqualTo(1);
        });
        CallView dialing = dialerCalls(campaign.id()).getFirst();
        assertThat(dialing.origin()).isEqualTo("DIALER");
        assertThat(dialing.status()).isEqualTo("DIALING");
        assertThat(dialing.operatorId()).isNull();
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ? and origin = 'CAMPAIGN'",
                dialing.id())).isEqualTo(1);

        event(dialing.id(), "ANSWERED", "HUMAN", null);
        CallView connected = call(dialing.id());
        assertThat(connected.operatorId()).isEqualTo(operatorX);
        assertThat(connected.connectedAt()).isNotNull();
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("ON_CALL");
        event(dialing.id(), "COMPLETED", null, "DEBTOR");
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("WRAP_UP");
        asOperator(operatorX, () -> calls.tabulate(dialing.id(), new TabulateCommand("CPC_NEGOTIATING", null, null, null,
                null)));
        assertThat(entries(campaign.id()).getFirst().status()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from collection.interaction where external_ref = ? and operator_id = ?",
                "call:" + dialing.id(), operatorX)).isEqualTo(1);

        CampaignStats stats = asSupervisor(() -> dialer.stats(campaign.id()));
        assertThat(stats.dialed()).isEqualTo(1);
        assertThat(stats.connected()).isEqualTo(1);
        assertThat(stats.abandonRate()).isEqualByComparingTo("0");
        assertThat(stats.asr()).isEqualByComparingTo("100");
        assertThat(stats.completed()).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'dialer.campaign_activated'"))
                .isEqualTo(1);
    }

    @Test
    void abandonmentVoicemailAndRetries() {
        CaseView first = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        CaseView second = loadCase(creditorA, portfolioA, debtor("111.444.777-35", "(11) 97777-1234"), "300.00");
        CampaignView campaign = campaign("ABAND", "PROGRESSIVE");
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        asOperator(operatorY, () -> agents.login(campaign.id()));
        drainTelephony();
        List<CallView> placed = dialerCalls(campaign.id());
        assertThat(placed).hasSize(2).allSatisfy(c -> assertThat(c.status()).isEqualTo("DIALING"));

        // os dois operadores pausam enquanto as chamadas tocam: a atendida por pessoa é abandonada
        asOperator(operatorX, () -> agents.pause("BREAK"));
        asOperator(operatorY, () -> agents.pause("TRAINING"));
        CallView human = placed.get(0);
        CallView machine = placed.get(1);
        event(human.id(), "ANSWERED", "HUMAN", null);
        event(machine.id(), "ANSWERED", "MACHINE", null);
        assertThat(call(human.id()).result()).isEqualTo("ABANDONED");
        assertThat(call(human.id()).abandoned()).isTrue();
        assertThat(call(machine.id()).result()).isEqualTo("VOICEMAIL");
        drainTelephony();
        assertThat(count("select count(*) from collection.interaction i join collection.tabulation t "
                + "on t.id = i.tabulation_id where i.external_ref = ? and t.code = 'CALL_ABANDONED' "
                + "and i.operator_id is null", "call:" + human.id())).isEqualTo(1);
        assertThat(count("select count(*) from collection.interaction i join collection.tabulation t "
                + "on t.id = i.tabulation_id where i.external_ref = ? and t.code = 'VOICEMAIL'", "call:" + machine.id()))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.abandoned'")).isEqualTo(1);

        assertThat(entries(campaign.id())).hasSize(2).allSatisfy(e -> {
            assertThat(e.status()).isEqualTo("PENDING");
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.nextAttemptAt()).isAfter(Instant.now().plusSeconds(25 * 60));
        });
        CampaignStats stats = asSupervisor(() -> dialer.stats(campaign.id()));
        assertThat(stats.live()).isEqualTo(1);
        assertThat(stats.abandoned()).isEqualTo(1);
        assertThat(stats.abandonRate()).isEqualByComparingTo("100");
        assertThat(stats.machine()).isEqualTo(1);
        assertThat(stats.availableAgents()).isZero();
        assertThat(List.of(first.id(), second.id())).containsAll(entries(campaign.id()).stream().map(EntryView::caseId)
                .toList());
    }

    @Test
    void gateDeniesTheDialerRestrictionExcludesAndWindowDefers() {
        UUID restricted = debtor("529.982.247-25");
        loadCase(creditorA, portfolioA, restricted, "900.00");
        system(() -> restrictions.create(new CreateRestrictionCommand(restricted, null, "PHONE", "OPT_OUT", null, null,
                "Opt-out", "DEBTOR", null, null)));
        CampaignView campaign = campaign("GATE", "PREDICTIVE");
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();
        // o caso segue elegível (outros canais livres); o porteiro nega o telefone na discagem e,
        // sem fim previsto para a restrição, o caso sai da lista
        assertThat(entries(campaign.id())).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("EXCLUDED");
            assertThat(e.excludedReason()).isEqualTo("CONTACT_RESTRICTED");
            assertThat(e.attempts()).isZero();
        });
        assertThat(count("select count(*) from telephony.phone_call where campaign_id = ? and status = 'FAILED' "
                + "and failure_code = 'CONTACT_DENIED'", campaign.id())).isEqualTo(1);
        assertThat(count("select count(*) from telephony.phone_call where campaign_id = ? and status = 'DIALING'",
                campaign.id())).isZero();
        assertThat(count("select count(*) from compliance.contact_attempt where origin = 'CAMPAIGN'")).isZero();

        // janela da política do credor fechada agora: adia sem consumir tentativa
        UUID other = debtor("111.444.777-35", "(11) 97777-1234");
        CaseView kase = loadCase(creditorA, portfolioA, other, "400.00");
        LocalTime now = ZonedDateTime.now(TimeConfig.BUSINESS_ZONE).toLocalTime();
        LocalTime start = now.plusHours(2).withSecond(0).withNano(0);
        system(() -> policies.publish(new PublishPolicyCommand(creditorA, null, "janela fechada", List.of(
                new ChannelRuleInput("PHONE", start, start.plusHours(1), ALL_DAYS, null, null, null, false)))));
        asSupervisor(() -> dialer.reload(campaign.id()));
        drainTelephony();
        EntryView deferred = entries(campaign.id()).stream().filter(e -> e.caseId().equals(kase.id())).findFirst()
                .orElseThrow();
        assertThat(deferred.status()).isEqualTo("PENDING");
        assertThat(deferred.attempts()).isZero();
        assertThat(deferred.lastDenial()).contains("OUTSIDE_WINDOW");
        assertThat(deferred.nextAttemptAt()).isAfter(Instant.now().plusSeconds(3600));
        CallView blocked = dialerCalls(campaign.id()).stream().filter(c -> c.caseId().equals(kase.id())).findFirst()
                .orElseThrow();
        assertThat(blocked.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.blocked' "
                + "and attributes ->> 'origin' = 'DIALER' and attributes ->> 'deferrable' = 'true'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.denied'")).isPositive();

        asSupervisor(() -> dialer.finish(campaign.id()));
        assertThat(entries(campaign.id())).allSatisfy(e -> assertThat(e.status()).isIn("EXCLUDED", "COMPLETED",
                "EXHAUSTED"));
    }
}
