package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseSheet;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.CallbackInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases.PromiseView;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.TaskView;
import br.com.gestorcobranca.collection.application.service.CollectionTestHooks;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos completos do módulo contra PostgreSQL real: abertura por carga de carteira, roteamento,
 * próximo caso, interações com promessa e retorno, cumprimento/quebra de promessa, vencimento de
 * retorno, contestação com evidência, recálculo de elegibilidade por restrição, acordo e
 * encerramento por quitação — com os eventos de integração consumidos (inclusive pelo incentive).
 */
class CollectionFlowIT extends CollectionTestSupport {

    @BeforeEach
    void setUp() {
        provision("coll");
    }

    @Test
    void portfolioLoadOpensRoutedEligibleCaseWithHistoryAndBusinessEvents() {
        QueueView queueA = asManager(() -> queues.create(queue("BANCO_A", 10, creditorA, "PULL", null, Set.of(team))));
        UUID debtor = debtor("526.018.159-06");
        load(creditorA, portfolioA, debtor, "100.00", "250.00");

        CaseView c = activeCase(debtor, portfolioA);
        assertThat(c.status()).isEqualTo("OPEN");
        assertThat(c.openTitles()).isEqualTo(2);
        assertThat(c.openBalance()).isEqualByComparingTo("350.00");
        assertThat(c.eligibleBalance()).isEqualByComparingTo("350.00");
        assertThat(c.eligible()).isTrue();
        assertThat(c.contactableChannels()).contains("PHONE", "EMAIL", "WHATSAPP", "SMS");
        assertThat(c.queueId()).isEqualTo(queueA.id());
        assertThat(c.ownerType()).isEqualTo("QUEUE");
        assertThat(count("select count(*) from collection.case_assignment where case_id = ? and mode = 'OPENED' "
                + "and valid_to is null and owner_type = 'QUEUE'", c.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'case.opened' and case_id = ?",
                c.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'collection_case.opened' and entity_id = ?",
                c.id())).isEqualTo(1);

        // reprocessar o mesmo lote não duplica o caso; título em outra carteira abre outro caso
        load(creditorA, portfolioA, debtor, "10.00");
        assertThat(count("select count(*) from collection.collection_case where debtor_id = ?", debtor)).isEqualTo(1);
        assertThat(activeCase(debtor, portfolioA).openTitles()).isEqualTo(3);
        load(creditorB, portfolioB, debtor, "80.00");
        assertThat(system(() -> cases.listByDebtor(debtor))).hasSize(2);
        assertThat(activeCase(debtor, portfolioB).queueId()).isNull();
        assertThat(activeCase(debtor, portfolioB).ownerType()).isEqualTo("NONE");

        // carteira ainda não operada: caso inelegível até a ativação (PortfolioStatusChanged → reavaliação)
        UUID draft = draftPortfolio(creditorA, "A-02");
        load(creditorA, draft, debtor, "40.00");
        CaseView pending = activeCase(debtor, draft);
        assertThat(pending.eligible()).isFalse();
        assertThat(pending.eligibilityReasons()).containsExactly("PORTFOLIO_NOT_OPERABLE");
        system(() -> portfolios.changeStatus(draft, "ACTIVE", "Início da operação"));
        awaitTrue("varredura da carteira", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.case_sweep' and payload ->> 'portfolioId' = ?", draft.toString()) >= 1);
        drainJobs();
        assertThat(activeCase(debtor, draft).eligible()).isTrue();
    }

    @Test
    void operatorWorksCaseFromNextCaseToPromiseKeptAndClosesBySettlement() {
        asManager(() -> queues.create(queue("BANCO_A", 10, creditorA, "PULL", null, Set.of(team))));
        UUID debtor = debtor("083.016.613-05");
        List<UUID> titles = load(creditorA, portfolioA, debtor, "300.00");

        CaseView next = asOperator(operatorX, () -> work.nextCase(null)).orElseThrow();
        assertThat(next.assigneeUserId()).isEqualTo(operatorX);
        assertThat(next.assigneeTeamId()).isEqualTo(team);
        assertThat(next.ownerType()).isEqualTo("USER");
        // outro operador não trabalha um caso que não é dele
        assertThatThrownBy(() -> record(operatorY, next.id(), interaction("NO_ANSWER", null, null, null)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("case.assigned_to_other");

        RecordResult noAnswer = record(operatorX, next.id(), interaction("NO_ANSWER", null, null, null));
        assertThat(noAnswer.interaction().effectiveContact()).isFalse();
        assertThat(noAnswer.collectionCase().status()).isEqualTo("OPEN");
        assertThatThrownBy(() -> record(operatorX, next.id(), interaction("REFUSED", null, null, null)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("interaction.note_required");
        assertThatThrownBy(() -> record(operatorX, next.id(), interaction("PROMISE", null, null, null)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("interaction.promise_required");

        LocalDate due = LocalDate.now(ZONE).plusDays(3);
        RecordResult promised = record(operatorX, next.id(), interaction("PROMISE",
                new PromiseInput(new BigDecimal("300.00"), due), null, "Paga na sexta"));
        assertThat(promised.collectionCase().status()).isEqualTo("PROMISE");
        assertThat(promised.promise().status()).isEqualTo("PENDING");
        assertThat(promised.promise().breaksAfter()).isEqualTo(due.plusDays(2));
        assertThat(promised.collectionCase().nextActionAt())
                .isEqualTo(due.plusDays(3).atStartOfDay(ZONE).toInstant());
        // observação gravada criptografada
        assertThat(count("select count(*) from collection.interaction where id = ? and note_encrypted not like '%sexta%'",
                promised.interaction().id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.effective' and case_id = ?",
                next.id())).isEqualTo(1);

        // pagamento confirmado sem acordo cumpre a promessa (vínculo: devedor, credor, carteira)
        UUID paymentId = Ids.newId();
        Instant paidAt = Instant.now();
        system(() -> ledger.applyPayment(new PaymentApplication(titles.getFirst(), paymentId,
                ComponentAmounts.principalOnly(new BigDecimal("300.00")), paidAt)));
        PaymentConfirmed confirmed = new PaymentConfirmed(Ids.newId(), tenant, paidAt, paymentId, debtor, creditorA,
                portfolioA, null, new BigDecimal("300.00"), paidAt, "EXTERNAL", "PIX",
                List.of(new PaymentConfirmed.Allocation(titles.getFirst(), null, new BigDecimal("300.00"))));
        publishAndAwait(confirmed, "collection.payment-confirmed");
        PromiseView kept = system(() -> promises.get(promised.promise().id()));
        assertThat(kept.status()).isEqualTo("KEPT");
        assertThat(kept.paidAmount()).isEqualByComparingTo("300.00");
        assertThat(count("select count(*) from shared.business_event where event_type = 'promise.kept'")).isEqualTo(1);

        // título quitado → caso encerrado (SETTLED), sem dono vigente
        awaitIdle("CollectionEventListeners");
        awaitTrue("caso encerrado", () -> count("select count(*) from collection.collection_case where id = ? "
                + "and status = 'CLOSED' and close_reason = 'SETTLED'", next.id()) == 1);
        assertThat(count("select count(*) from collection.case_assignment where case_id = ? and valid_to is null",
                next.id())).isZero();
        assertThat(count("select count(*) from collection.case_assignment where case_id = ? and mode = 'CLOSED'",
                next.id())).isEqualTo(1);
        // incentive consumiu os fatos de interação e de atribuição com os campos novos
        awaitIdle("IncentiveEventListeners");
        assertThat(count("select count(*) from shared.consumed_event where consumer = 'incentive.interaction-recorded' "
                + "and tenant_id = ?", tenant)).isEqualTo(2);
        assertThat(count("select count(*) from shared.consumed_event where consumer = 'incentive.case-assignment' "
                + "and tenant_id = ?", tenant)).isGreaterThanOrEqualTo(1);

        // estorno reabre a promessa (o caso já encerrado não é reaberto pelo estorno da promessa)
        publishAndAwait(new PaymentReversed(Ids.newId(), tenant, Instant.now(), Ids.newId(), paymentId, debtor, creditorA,
                portfolioA, null, new BigDecimal("300.00"), "chargeback"), "collection.payment-reversed");
        assertThat(system(() -> promises.get(promised.promise().id())).status()).isEqualTo("PENDING");
    }

    @Test
    void callbackIsKeptByInteractionOrMarkedMissedByTheJob() {
        asManager(() -> queues.create(queue("BANCO_A", 10, creditorA, "PULL", null, Set.of(team))));
        system(() -> settings.update(new SettingsCommand(1, 30, 15, 10)));
        UUID debtor = debtor("186.091.390-34");
        load(creditorA, portfolioA, debtor, "500.00");
        CaseView c = asOperator(operatorX, () -> work.nextCase(null)).orElseThrow();

        LocalDateTime inTwoHours = LocalDateTime.now(ZONE).plusHours(2).withNano(0);
        RecordResult scheduled = record(operatorX, c.id(), interaction("CALLBACK", null,
                new CallbackInput(inTwoHours, null, "ligar após o almoço"), null));
        TaskView callback = scheduled.callback();
        assertThat(callback.status()).isEqualTo("PENDING");
        assertThat(callback.dueAtLocal()).isEqualTo(inTwoHours);
        assertThat(callback.assigneeUserId()).isEqualTo(operatorX);
        assertThat(callback.toleranceUntil()).isEqualTo(callback.dueAt().plus(Duration.ofMinutes(15)));
        assertThat(scheduled.collectionCase().nextActionAt()).isEqualTo(callback.dueAt());

        // sem retorno vencido, o próximo caso não devolve o caso dele (vem da fila — vazia)
        assertThat(asOperator(operatorX, () -> work.nextCase(null))).isEmpty();

        // passa a tolerância: job marca atrasado e o caso volta como próxima ação do operador
        clock.advance(Duration.ofHours(3));
        assertThat(system(() -> CollectionTestHooks.markMissedCallbacks(applicationContext))).isEqualTo(1);
        assertThat(system(() -> tasks.listByCase(c.id())).getFirst().status()).isEqualTo("MISSED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'callback.missed'")).isEqualTo(1);
        assertThat(asOperator(operatorX, () -> work.nextCase(null))).get().extracting(CaseView::id).isEqualTo(c.id());

        // interação posterior conclui o retorno, mas com atraso (não conta como cumprido)
        RecordResult late = record(operatorX, c.id(), interaction("NO_ANSWER", null, null, null));
        TaskView done = system(() -> tasks.listByCase(c.id())).getFirst();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.completedOnTime()).isFalse();
        assertThat(done.completionInteractionId()).isEqualTo(late.interaction().id());

        // novo retorno cumprido dentro da tolerância
        clock.reset();
        RecordResult again = record(operatorX, c.id(), interaction("CALLBACK", null,
                new CallbackInput(LocalDateTime.now(ZONE).plusMinutes(10).withNano(0), null, null), null));
        RecordResult onTime = record(operatorX, c.id(), interaction("NO_ANSWER", null, null, null));
        TaskView kept = system(() -> tasks.listByCase(c.id())).stream().filter(t -> t.id().equals(again.callback().id()))
                .findFirst().orElseThrow();
        assertThat(kept.completedOnTime()).isTrue();
        assertThat(kept.completionInteractionId()).isEqualTo(onTime.interaction().id());
        assertThat(count("select count(*) from shared.business_event where event_type = 'callback.kept'")).isEqualTo(1);
    }

    @Test
    void promiseIsBrokenAfterToleranceAndAgreementMovesTheCase() {
        system(() -> settings.update(new SettingsCommand(0, 30, 30, 10)));
        UUID debtor = debtor("996.030.824-30");
        load(creditorA, portfolioA, debtor, "400.00");
        CaseView c = activeCase(debtor, portfolioA);
        LocalDate due = LocalDate.now(ZONE).plusDays(1);
        RecordResult promised = record(operatorX, c.id(), interaction("PROMISE", new PromiseInput(new BigDecimal("150.00"),
                due), null, null));
        assertThat(promised.collectionCase().assigneeUserId()).isEqualTo(operatorX);
        assertThat(count("select count(*) from collection.case_assignment where case_id = ? and mode = 'INTERACTION'",
                c.id())).isEqualTo(1);

        assertThat(system(() -> CollectionTestHooks.breakPromises(applicationContext))).isZero();
        clock.advance(Duration.ofDays(3));
        assertThat(system(() -> CollectionTestHooks.breakPromises(applicationContext))).isEqualTo(1);
        assertThat(system(() -> promises.get(promised.promise().id())).status()).isEqualTo("BROKEN");
        CaseView afterBreak = system(() -> cases.get(c.id()));
        assertThat(afterBreak.status()).isEqualTo("IN_NEGOTIATION");
        assertThat(afterBreak.nextActionAt()).isNull();
        clock.reset();

        // acordo formalizado (negotiation) → AGREEMENT, substituindo nova promessa pendente
        RecordResult second = record(operatorX, c.id(), interaction("PROMISE", new PromiseInput(new BigDecimal("50.00"),
                LocalDate.now(ZONE).plusDays(5)), null, null));
        publishAndAwait(new AgreementStatusChanged(Ids.newId(), tenant, Instant.now(), Ids.newId(), c.id(), debtor,
                creditorA, portfolioA, List.of(), null, "FORMALIZED", new BigDecimal("400.00"), null),
                "collection.agreement-status");
        assertThat(system(() -> cases.get(c.id())).status()).isEqualTo("AGREEMENT");
        assertThat(system(() -> promises.get(second.promise().id())).status()).isEqualTo("CANCELLED");
        publishAndAwait(new AgreementStatusChanged(Ids.newId(), tenant, Instant.now(), Ids.newId(), c.id(), debtor,
                creditorA, portfolioA, List.of(), "FORMALIZED", "BROKEN", new BigDecimal("400.00"), null),
                "collection.agreement-status");
        assertThat(system(() -> cases.get(c.id())).status()).isEqualTo("IN_NEGOTIATION");
        assertThat(count("select count(*) from shared.business_event where event_type = 'promise.broken'")).isEqualTo(1);
    }

    @Test
    void disputeBlocksCollectionUntilResolvedWithEvidence() {
        UUID debtor = debtor("628.194.821-12");
        List<UUID> titles = load(creditorA, portfolioA, debtor, "120.00", "80.00");
        CaseView c = activeCase(debtor, portfolioA);
        asOperator(operatorX, () -> work.takeOver(c.id(), "atendimento"));

        DisputeView dispute = asOperator(operatorX, () -> disputes.open(c.id(), new OpenDisputeCommand(
                Set.of(titles.getFirst()), "ALREADY_PAID", "OPERATOR", "Devedor diz ter pago no caixa", null, null)));
        assertThat(dispute.status()).isEqualTo("OPEN");
        assertThat(dispute.blocksCollection()).isTrue();
        assertThat(dispute.disputedAmount()).isEqualByComparingTo("120.00");
        assertThat(dispute.responseDueDate()).isEqualTo(LocalDate.now(ZONE).plusDays(10));
        awaitIdle("CollectionEventListeners");
        CaseView held = system(() -> cases.get(c.id()));
        assertThat(held.status()).isEqualTo("DISPUTED");
        assertThat(held.eligible()).isFalse();
        assertThat(held.eligibilityReasons()).contains("DISPUTE_OPEN");
        assertThat(held.disputedTitles()).isEqualTo(1);
        assertThat(held.eligibleBalance()).isEqualByComparingTo("80.00");
        assertThat(count("select count(*) from debt.debt_title where id = ? and dispute_blocked", titles.getFirst()))
                .isEqualTo(1);

        EvidenceView evidence = asOperator(operatorX, () -> disputes.attachEvidence(dispute.id(), new EvidenceUpload(
                "comprovante.txt", "text/plain", 9, new ByteArrayInputStream("pago 120".getBytes(StandardCharsets.UTF_8)))));
        assertThat(evidence.sha256()).hasSize(64);
        assertThatThrownBy(() -> asOperator(operatorX, () -> disputes.attachEvidence(dispute.id(), new EvidenceUpload(
                "x.exe", "application/x-msdownload", 3, new ByteArrayInputStream(new byte[]{1, 2, 3})))))
                .isInstanceOf(BusinessRuleException.class);
        try (var in = asOperator(operatorX, () -> disputes.openEvidence(dispute.id(), evidence.id())).content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("pago 120");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' "
                + "and details ->> 'resource' = 'dispute_evidence'")).isEqualTo(1);

        // prazo vencido sinalizado pelo job diário
        clock.advance(Duration.ofDays(12));
        assertThat(system(() -> CollectionTestHooks.flagOverdueDisputes(applicationContext))).isEqualTo(1);
        clock.reset();

        DisputeView resolved = asSupervisor(() -> disputes.resolve(dispute.id(), "REJECTED", "Pagamento não localizado"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.overdue()).isTrue();
        CaseView back = system(() -> cases.get(c.id()));
        assertThat(back.status()).isEqualTo("OPEN");
        assertThat(back.eligible()).isTrue();
        assertThat(back.disputedTitles()).isZero();
        assertThat(count("select count(*) from debt.debt_title where id = ? and dispute_blocked", titles.getFirst())).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.resolved'")).isEqualTo(1);
        // evidência é imutável no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update collection.dispute_evidence set file_name = 'x' where id = ?", evidence.id())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void contactRestrictionAndPolicyEventsRecalculateEligibility() {
        UUID debtor = debtor("993.518.190-19");
        load(creditorA, portfolioA, debtor, "70.00");
        CaseView c = activeCase(debtor, portfolioA);
        assertThat(c.eligible()).isTrue();

        RestrictionView blocked = system(() -> restrictions.create(new CreateRestrictionCommand(debtor, null, null,
                "LEGAL_BLOCK", null, null, "Liminar", "LEGAL", null, null)));
        awaitTrue("recálculo após restrição", () -> !system(() -> cases.get(c.id())).eligible());
        CaseView restricted = system(() -> cases.get(c.id()));
        assertThat(restricted.eligibilityReasons()).containsExactly("CONTACT_RESTRICTED");
        assertThat(restricted.contactableChannels()).isEmpty();
        assertThat(count("select count(*) from shared.business_event where event_type = 'case.eligibility_changed' "
                + "and case_id = ?", c.id())).isEqualTo(1);

        CaseSheet sheet = system(() -> cases.sheet(c.id()));
        assertThat(sheet.activeRestrictions()).extracting(r -> r.id()).containsExactly(blocked.id());
        assertThat(sheet.debtor().maskedDocument()).doesNotContain("993.518");
        assertThat(sheet.titles()).hasSize(1);

        // canal restrito só num canal: continua elegível pelos demais
        system(() -> restrictions.revoke(blocked.id(), "Liminar revogada"));
        awaitTrue("recálculo após revogação", () -> system(() -> cases.get(c.id())).eligible());
        system(() -> restrictions.create(new CreateRestrictionCommand(debtor, creditorA, "WHATSAPP", "CREDITOR_REQUEST",
                null, null, "Credor pediu", "CREDITOR", null, null)));
        awaitTrue("canal restrito", () -> !system(() -> cases.get(c.id())).contactableChannels().contains("WHATSAPP"));
        assertThat(system(() -> cases.get(c.id())).eligible()).isTrue();
    }

    @Test
    void manualLifecycleIsValidatedAndAudited() {
        UUID debtor = debtor("937.865.797-41");
        load(creditorA, portfolioA, debtor, "90.00");
        CaseView c = activeCase(debtor, portfolioA);
        assertThatThrownBy(() -> asSupervisor(() -> cases.resume(c.id(), "x")))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("case.not_suspended");
        CaseView suspended = asSupervisor(() -> cases.suspend(c.id(), "Pedido do credor"));
        assertThat(suspended.status()).isEqualTo("SUSPENDED");
        assertThat(suspended.eligibilityReasons()).containsExactly("CASE_SUSPENDED");
        CaseView legal = asSupervisor(() -> cases.sendToLegal(c.id(), "Ajuizamento"));
        assertThat(legal.status()).isEqualTo("LEGAL");
        assertThat(asSupervisor(() -> cases.returnFromLegal(c.id(), "Retorno")).status()).isEqualTo("OPEN");
        CaseView closed = asSupervisor(() -> cases.close(c.id(), "Devedor falecido"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closeReason()).isEqualTo("MANUAL");
        assertThatThrownBy(() -> asSupervisor(() -> cases.suspend(c.id(), "x"))).isInstanceOf(ConflictException.class);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and action in ('collection_case.status_changed', 'collection_case.closed')", c.id())).isEqualTo(4);
        assertThat(count("select count(*) from shared.business_event where event_type = 'case.closed' and case_id = ?",
                c.id())).isEqualTo(1);
    }
}
