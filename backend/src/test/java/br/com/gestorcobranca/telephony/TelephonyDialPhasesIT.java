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
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Discagem em fases (TEL-01 e TEL-02): o pedido é confirmado antes do HTTP ao provedor, o HTTP corre sem
 * transação e o resultado é gravado depois. No sandbox, destinos terminados em {@code 7777} entregam o toque
 * e o atendimento (como webhooks) antes da resposta ao pedido; em {@code 9999}, falha transitória do lote.
 * <p>
 * Comandos à chamada em andamento ({@code bridge}/{@code hangup}) como jobs duráveis gravados na transação que
 * decide o estado local e executados logo após o commit: transação desfeita → nenhum comando; execução imediata que
 * não aconteceu → o worker envia; execuções concorrentes → um só envio; conexão interrompida → não reenviada.
 */
class TelephonyDialPhasesIT extends TelephonyTestSupport {

    @Autowired
    TelephonyRoutingUseCases routing;
    @Autowired
    JobQueue jobQueue;
    @Autowired
    MeterRegistry meterRegistry;

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
        // a conexão no provedor foi gravada como job na transação do atendimento e executada uma vez
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.bridge' and status = 'DONE' "
                + "and attempts = 1 and payload ->> 'callId' = ?", connected.id().toString())).isEqualTo(1);
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

    // ------------------------------------------------------------------ comandos à chamada em andamento como jobs

    @Test
    void operatorHangupIsRecordedWithTheCallAndSentRightAfterCommit() {
        CallView answered = answeredClickToCall("100.00");
        double before = sent("hangup");

        asOperator(operatorX, () -> calls.hangup(answered.id()));

        // sem o ciclo do worker (agendamento desligado nos testes): só a execução imediata depois do commit envia
        awaitTrue("encerramento no provedor", () -> sent("hangup") == before + 1);
        awaitTrue("job concluído", () -> count("select count(*) from shared.processing_job where type = 'telephony.hangup' "
                + "and status = 'DONE' and attempts = 1 and payload ->> 'callId' = ?", answered.id().toString()) == 1);
        assertThat(call(answered.id()).status()).isEqualTo("COMPLETED");
        // chave por chamada e id no provedor
        assertThat(string("select dedup_key from shared.processing_job where type = 'telephony.hangup' "
                + "and payload ->> 'callId' = ?", answered.id().toString())).isEqualTo("hangup:" + answered.id() + ":"
                + string("select provider_call_id from telephony.phone_call where id = ?", answered.id()));
    }

    @Test
    void rolledBackHangupKeepsNoCommandAndSendsNothing() {
        CallView answered = answeredClickToCall("110.00");
        double before = sent("hangup");

        assertThatThrownBy(() -> asOperator(operatorX, () -> {
            calls.hangup(answered.id());
            throw new IllegalStateException("operação desfeita depois do encerramento");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.hangup' "
                + "and payload ->> 'callId' = ?", answered.id().toString())).isZero();
        assertThat(call(answered.id()).status()).isEqualTo("ANSWERED");
        drainTelephony();
        assertThat(sent("hangup")).isEqualTo(before);
    }

    @Test
    void commandWhoseImmediateExecutionNeverHappenedIsSentByTheWorker() {
        CallView answered = answeredClickToCall("120.00");
        double before = sent("hangup");
        // estado deixado por uma instância que caiu entre o commit e a execução imediata: job confirmado e pendente
        UUID job = hangupJob(answered.id());
        assertThat(jobStatus(job)).isEqualTo("PENDING");
        assertThat(sent("hangup")).isEqualTo(before);

        drainTelephony();

        assertThat(jobStatus(job)).isEqualTo("DONE");
        assertThat(sent("hangup")).isEqualTo(before + 1);
    }

    @Test
    void concurrentExecutionsOfTheSameCommandSendItOnce() throws InterruptedException {
        CallView answered = answeredClickToCall("130.00");
        double before = sent("hangup");
        UUID job = hangupJob(answered.id());

        // execuções imediatas (como as de duas instâncias) e ciclos do worker disputando o mesmo job
        List<Thread> contenders = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            boolean dispatch = i % 2 == 0;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (dispatch) {
                    JobDrain.dispatch(applicationContext, job);
                } else {
                    JobDrain.pollOnce(applicationContext);
                }
            });
            thread.start();
            contenders.add(thread);
        }
        start.countDown();
        for (Thread thread : contenders) {
            thread.join(10_000);
        }
        drainTelephony();

        assertThat(jobStatus(job)).isEqualTo("DONE");
        assertThat(count("select attempts from shared.processing_job where id = ?", job)).isEqualTo(1);
        assertThat(sent("hangup")).isEqualTo(before + 1);
    }

    @Test
    void bridgeAlreadyRequestedIsNotSentAgain() {
        CallView connected = bridgedDialerCall("INTERROMPIDO");
        // o job real gravou a marca antes do HTTP
        assertThat(string("select bridge_requested_at::text from telephony.phone_call where id = ?", connected.id()))
                .isNotNull();
        double before = sent("bridge");
        double interrupted = bridgeOutcome("interrupted");

        // nova execução do comando (ex.: a instância caiu durante o pedido e o lease expirou): a marca decide
        UUID job = bridgeJob(connected.id(), 1);
        drainTelephony();

        assertThat(jobStatus(job)).isEqualTo("DONE");
        assertThat(sent("bridge")).isEqualTo(before);
        assertThat(bridgeOutcome("interrupted")).isEqualTo(interrupted + 1);
        CallView still = call(connected.id());
        assertThat(still.status()).isEqualTo("ANSWERED");
        assertThat(still.operatorId()).isEqualTo(operatorX);
    }

    @Test
    void bridgeWhosePreviousAttemptFailedBeforeTheRequestIsSent() {
        CallView connected = bridgedDialerCall("PREPARO");
        double before = sent("bridge");
        // a tentativa anterior falhou no preparo: a marca não chegou a ser confirmada
        system(() -> jdbcTemplate.update("update telephony.phone_call set bridge_requested_at = null where id = ?",
                connected.id()));

        UUID job = bridgeJob(connected.id(), 1);
        drainTelephony();

        assertThat(jobStatus(job)).isEqualTo("DONE");
        assertThat(sent("bridge")).isEqualTo(before + 1);
        assertThat(string("select bridge_requested_at::text from telephony.phone_call where id = ?", connected.id()))
                .isNotNull();
    }

    @Test
    void bridgeTheProviderDidNotMakeIsUndone() {
        // destino 8888: o simulador aceita a discagem e falha a conexão ao operador
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25", "(11) 98765-8888"), "900.00");
        CampaignView campaign = campaign("SEMPONTE");
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();
        CallView dialing = dialerCalls(campaign.id()).getFirst();
        assertThat(dialing.caseId()).isEqualTo(kase.id());

        assertThat(event(dialing.id(), "RINGING", null, null)).isEqualTo("applied");
        assertThat(event(dialing.id(), "ANSWERED", "HUMAN", null)).isEqualTo("applied");
        drainTelephony();

        CallView abandoned = call(dialing.id());
        assertThat(abandoned.status()).isEqualTo("COMPLETED");
        assertThat(abandoned.abandoned()).isTrue();
        assertThat(abandoned.operatorId()).isNull();
        assertThat(asOperator(operatorX, agents::me).status()).isNotEqualTo("ON_CALL");
        assertThat(count("select count(*) from shared.processing_job where type = 'telephony.hangup' and status = 'DONE' "
                + "and payload ->> 'callId' = ? and payload ->> 'reason' = 'ABANDONED'", dialing.id().toString()))
                .isEqualTo(1);
    }

    @Test
    void failureToUndoTheBridgeSendsTheJobToDeadWithoutAskingAgain() {
        // clique para ligar atendido (o operador já está na linha) cuja conexão falha no provedor (destino 8888):
        // desfazer a conexão não se aplica ao clique para ligar, então a compensação falha
        // o teste depende de uma assimetria: Call#bridgeRequested aceita o clique para ligar e Call#bridgeFailed
        // recusa; o fluxo real não enfileira telephony.bridge no clique para ligar. Se bridgeRequested passar a
        // recusá-lo, a falha migra para o preparo (sem marca) e este teste precisa forçar a compensação de outro jeito
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25", "(11) 98765-8888"), "150.00");
        asOperator(operatorX, () -> agents.login(null));
        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        assertThat(event(queued.id(), "RINGING", null, null)).isEqualTo("applied");
        assertThat(event(queued.id(), "ANSWERED", null, null)).isEqualTo("applied");
        assertThat(call(queued.id()).connectedAt()).isNotNull();
        double failed = meterRegistry.counter("integration.calls", "provider", "sandbox", "operation", "bridge",
                "outcome", "transient").count();

        UUID job = bridgeJob(queued.id(), 0);
        drainTelephony();

        assertThat(jobStatus(job)).isEqualTo("DEAD");
        assertThat(count("select attempts from shared.processing_job where id = ?", job)).isEqualTo(1);
        assertThat(string("select last_error from shared.processing_job where id = ?", job))
                .contains("PermanentJobFailure");
        // um único pedido ao provedor, e a marca impede outro
        assertThat(meterRegistry.counter("integration.calls", "provider", "sandbox", "operation", "bridge", "outcome",
                "transient").count()).isEqualTo(failed + 1);
        assertThat(string("select bridge_requested_at::text from telephony.phone_call where id = ?", queued.id()))
                .isNotNull();
    }

    @Test
    void hangupsOfTheSameCallWithDifferentProviderIdsAreBothSent() {
        CallView answered = answeredClickToCall("140.00");
        double before = sent("hangup");
        String providerCallId = string("select provider_call_id from telephony.phone_call where id = ?", answered.id());

        // o encerramento da chamada e o de um aceite tardio com outro id (outra chamada criada no provedor)
        UUID first = hangupJob(answered.id(), providerCallId);
        UUID second = hangupJob(answered.id(), "sbx-call-tardio");
        assertThat(second).isNotEqualTo(first);
        // o mesmo par (chamada, id) não duplica
        assertThat(hangupJob(answered.id(), providerCallId)).isEqualTo(first);
        drainTelephony();

        assertThat(jobStatus(first)).isEqualTo("DONE");
        assertThat(jobStatus(second)).isEqualTo("DONE");
        assertThat(sent("hangup")).isEqualTo(before + 2);
    }

    /** Chamada do discador atendida antes da resposta do lote (destino {@code 7777}) e conectada ao operador X. */
    private CallView bridgedDialerCall(String code) {
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25", "(11) 98765-7777"), "900.00");
        CampaignView campaign = campaign(code);
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();
        CallView connected = dialerCalls(campaign.id()).getFirst();
        assertThat(connected.caseId()).isEqualTo(kase.id());
        assertThat(connected.connectedAt()).isNotNull();
        return connected;
    }

    /** Job de conexão confirmado, com {@code attempts} tentativas anteriores já consumidas. */
    private UUID bridgeJob(UUID callId, int attempts) {
        UUID job = system(() -> jobQueue.enqueue("telephony.bridge", Map.of("callId", callId, "accountId", account.id(),
                "operatorId", operatorX), null, null));
        system(() -> jdbcTemplate.update("update shared.processing_job set attempts = ? where id = ?", attempts, job));
        return job;
    }

    private double bridgeOutcome(String outcome) {
        return meterRegistry.counter("telephony.provider.commands", "provider", "sandbox", "operation", "bridge",
                "outcome", outcome).count();
    }

    /** Clique para ligar atendido (destino {@code 7777}: eventos antes da resposta) e conectado ao operador X. */
    private CallView answeredClickToCall(String principal) {
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25", "(11) 98765-7777"), principal);
        asOperator(operatorX, () -> agents.login(null));
        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        CallView answered = call(queued.id());
        assertThat(answered.status()).isEqualTo("ANSWERED");
        return answered;
    }

    /** Job de encerramento confirmado sem execução imediata (mesma chave de {@code ProviderCalls}). */
    private UUID hangupJob(UUID callId) {
        return hangupJob(callId, string("select provider_call_id from telephony.phone_call where id = ?", callId));
    }

    private UUID hangupJob(UUID callId, String providerCallId) {
        return system(() -> jobQueue.enqueue("telephony.hangup", Map.of("callId", callId, "reason", "OPERATOR",
                "accountId", account.id(), "providerCallId", providerCallId), null,
                "hangup:" + callId + ":" + providerCallId));
    }

    private String jobStatus(UUID job) {
        return string("select status from shared.processing_job where id = ?", job);
    }

    /** Pedidos aceitos pelo simulador. */
    private double sent(String operation) {
        return meterRegistry.counter("integration.calls", "provider", "sandbox", "operation", operation, "outcome",
                "success").count();
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
