package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.AgentView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.PromiseInput;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.TabulateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Clique para ligar no sandbox até a interação no caso: porteiro na verificação prévia e na
 * discagem, eventos do provedor, presença do operador, pós-atendimento, tabulação pelo port do
 * collection e {@code CallCompleted} consumido pelo incentive.
 */
class TelephonyCallFlowIT extends TelephonyTestSupport {

    UUID debtor;
    CaseView kase;

    @BeforeEach
    void setUp() {
        provision("tel", false);
        debtor = debtor("529.982.247-25");
        kase = loadCase(creditorA, portfolioA, debtor, "800.00");
    }

    @Test
    void clickToCallUntilTheInteractionIsRecordedInTheCase() {
        assertThatThrownBy(() -> asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("disponível");
        asOperator(operatorX, () -> agents.login(null));

        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), "k-1"));
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.numberMasked()).doesNotContain("98765");
        // reenvio com a mesma chave devolve a mesma chamada
        assertThat(asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), "k-1")).id())
                .isEqualTo(queued.id());
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("ON_CALL");
        assertThat(count("select count(*) from telephony.phone_call where id = ? and number_encrypted not like '%98765%' "
                + "and number_hash is not null", queued.id())).isEqualTo(1);

        drainTelephony();
        CallView dialing = call(queued.id());
        assertThat(dialing.status()).isEqualTo("DIALING");
        assertThat(count("select count(*) from compliance.contact_attempt where reference_type = 'call' "
                + "and reference_id = ? and origin = 'OPERATOR' and channel = 'PHONE'", queued.id())).isEqualTo(1);

        assertThat(event(queued.id(), "RINGING", null, null)).isEqualTo("applied");
        assertThat(event(queued.id(), "ANSWERED", "HUMAN", null)).isEqualTo("applied");
        assertThat(event(queued.id(), "RINGING", null, null)).isEqualTo("stale");
        assertThat(event(queued.id(), "COMPLETED", null, "DEBTOR")).isEqualTo("applied");

        CallView ended = call(queued.id());
        assertThat(ended.status()).isEqualTo("COMPLETED");
        assertThat(ended.result()).isEqualTo("ANSWERED");
        assertThat(ended.interactionState()).isEqualTo("AWAITING_TABULATION");
        assertThat(ended.suggestedTabulation()).isEqualTo("CALL_ANSWERED");
        assertThat(ended.talkSeconds()).isNotNull();
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("WRAP_UP");
        assertThat(asOperator(operatorX, calls::awaitingTabulation)).extracting(CallView::id).containsExactly(queued.id());
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.tabulation_deadline' "
                + "and status = 'PENDING' and dedup_key = ?", "tabulation:" + queued.id())).isEqualTo(1);

        CallView tabulated = asOperator(operatorX, () -> calls.tabulate(queued.id(), new TabulateCommand("PROMISE", null,
                new PromiseInput(new BigDecimal("300.00"), LocalDate.now().plusDays(5)), null, null)));
        assertThat(tabulated.interactionState()).isEqualTo("RECORDED");
        assertThat(tabulated.tabulationCode()).isEqualTo("PROMISE");
        assertThat(tabulated.wrapUpSeconds()).isNotNull();
        assertThat(count("select count(*) from collection.interaction where id = ? and case_id = ? and channel = 'PHONE' "
                + "and source = 'TELEPHONY' and external_ref = ? and operator_id = ? and effective_contact",
                tabulated.interactionId(), kase.id(), "call:" + queued.id(), operatorX)).isEqualTo(1);
        assertThat(count("select count(*) from collection.promise where case_id = ?", kase.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.tabulation_deadline' "
                + "and status = 'CANCELLED' and dedup_key = ?", "tabulation:" + queued.id())).isEqualTo(1);

        AgentView after = asOperator(operatorX, agents::me);
        assertThat(after.status()).isEqualTo("AVAILABLE");
        assertThat(count("select count(*) from telephony.presence_interval where user_id = ? and status = 'ON_CALL'",
                operatorX)).isEqualTo(1);
        assertThat(count("select count(*) from telephony.presence_interval where user_id = ? and status = 'WRAP_UP'",
                operatorX)).isEqualTo(1);
        for (String type : new String[]{"call.dialed", "call.completed", "call.tabulated", "agent.presence_changed"}) {
            assertThat(count("select count(*) from shared.business_event where event_type = ?", type))
                    .as(type).isPositive();
        }
        assertThat(string("select attributes ->> 'result' from shared.business_event where event_type = 'call.completed' "
                + "and reference_id = ?", queued.id())).isEqualTo("ANSWERED");
        awaitTrue("CallCompleted consumido pelo incentive", () -> count("select count(*) from shared.consumed_event "
                + "where consumer = 'incentive.call-completed' and tenant_id = ?", tenant) == 1);
        assertThat(count("select count(*) from shared.audit_event where entity_type = 'telephony_agent'")).isEqualTo(3);
    }

    @Test
    void gateDeniesAtPrecheckAndAtDialTime() {
        asOperator(operatorX, () -> agents.login(null));
        UUID restriction = system(() -> restrictions.create(new CreateRestrictionCommand(debtor, null, "PHONE",
                "DEBTOR_REQUEST", null, null, "Pediu para não receber ligações", "DEBTOR", null, null)).id());
        CallView blocked = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        assertThat(blocked.status()).isEqualTo("FAILED");
        assertThat(blocked.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(blocked.denialReasons()).contains("RESTRICTED_CHANNEL");
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("AVAILABLE");
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.dial' "
                + "and payload ->> 'callId' = ?", blocked.id().toString())).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.blocked' "
                + "and reference_id = ? and attributes ->> 'stage' = 'precheck'", blocked.id())).isEqualTo(1);

        // aceito na verificação prévia; restrição criada antes da discagem: o job é negado pelo porteiro
        system(() -> restrictions.revoke(restriction, "Revogado"));
        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        assertThat(queued.status()).isEqualTo("QUEUED");
        system(() -> restrictions.create(new CreateRestrictionCommand(debtor, creditorA, null, "CREDITOR_REQUEST", null,
                null, "Credor suspendeu", "CREDITOR", null, null)));
        drainTelephony();
        CallView denied = call(queued.id());
        assertThat(denied.status()).isEqualTo("FAILED");
        assertThat(denied.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(denied.interactionState()).isEqualTo("SKIPPED");
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", queued.id())).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.denied'")).isPositive();
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("AVAILABLE");
        assertThat(count("select count(*) from collection.interaction where case_id = ?", kase.id())).isZero();
    }

    @Test
    void unansweredCallIsTabulatedWithTheSuggestionAndHangupCancels() {
        asOperator(operatorX, () -> agents.login(null));
        CallView first = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        event(first.id(), "NO_ANSWER", null, null);
        CallView noAnswer = call(first.id());
        assertThat(noAnswer.result()).isEqualTo("NO_ANSWER");
        assertThat(noAnswer.suggestedTabulation()).isEqualTo("NO_ANSWER");
        assertThatThrownBy(() -> asOperator(operatorY, () -> calls.tabulate(first.id(),
                new TabulateCommand("NO_ANSWER", null, null, null, null))))
                .isInstanceOf(br.com.gestorcobranca.shared.security.ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Tabule");
        CallView tabulated = asOperator(operatorX, () -> calls.tabulate(first.id(),
                new TabulateCommand("NO_ANSWER", null, null, null, null)));
        assertThat(count("select count(*) from collection.interaction where id = ? and not effective_contact",
                tabulated.interactionId())).isEqualTo(1);

        CallView second = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        CallView hungUp = asOperator(operatorX, () -> calls.hangup(second.id()));
        assertThat(hungUp.status()).isEqualTo("CANCELLED");
        assertThat(hungUp.hangupSide()).isEqualTo("OPERATOR");
        assertThat(event(second.id(), "COMPLETED", null, "DEBTOR")).isEqualTo("stale");
        assertThat(asOperator(operatorX, agents::me).status()).isEqualTo("WRAP_UP");
        // pause e saída só depois da tabulação
        assertThatThrownBy(() -> asOperator(operatorX, () -> agents.pause("BREAK"))).isInstanceOf(ConflictException.class);
        asOperator(operatorX, () -> calls.tabulate(second.id(), new TabulateCommand("NO_ANSWER", null, null, null, null)));
        assertThat(asOperator(operatorX, () -> agents.pause("LUNCH")).pauseReason()).isEqualTo("LUNCH");
        assertThat(asOperator(operatorX, agents::logout).status()).isEqualTo("OFFLINE");
    }

    @Test
    void inboundCallIsIdentifiedByPreviousCallsAndDistributedToTheCaseOwner() {
        asOperator(operatorX, () -> agents.login(null));
        CallView outbound = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        event(outbound.id(), "BUSY", null, null);
        asOperator(operatorX, () -> calls.tabulate(outbound.id(), new TabulateCommand("BUSY", null, null, null, null)));

        UUID inbound = asAdmin(() -> sandbox.simulateInbound(account.id(), "+5511987654321"));
        CallView received = call(inbound);
        assertThat(received.direction()).isEqualTo("INBOUND");
        assertThat(received.caseId()).isEqualTo(kase.id());
        assertThat(received.status()).isEqualTo("ANSWERED");
        assertThat(received.operatorId()).isEqualTo(operatorX);
        event(inbound, "COMPLETED", null, "DEBTOR");
        CallView tabulated = asOperator(operatorX, () -> calls.tabulate(inbound,
                new TabulateCommand("CPC_NEGOTIATING", null, null, null, null)));
        assertThat(count("select count(*) from collection.interaction where id = ? and direction = 'INBOUND'",
                tabulated.interactionId())).isEqualTo(1);
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", inbound)).isZero();

        // sem operador disponível: chamada perdida, registrada automaticamente
        asOperator(operatorX, agents::logout);
        UUID missed = asAdmin(() -> sandbox.simulateInbound(account.id(), "+5511987654321"));
        assertThat(call(missed).result()).isEqualTo("NO_ANSWER");
        drainTelephony();
        assertThat(call(missed).interactionState()).isEqualTo("RECORDED");
    }
}
