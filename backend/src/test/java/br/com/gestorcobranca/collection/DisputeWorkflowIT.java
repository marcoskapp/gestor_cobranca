package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DeadlineRuleCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DisputeSettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeEvidenceLinkUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeDetail;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeQuery;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.ResolveCommand;
import br.com.gestorcobranca.collection.application.port.out.CollectionPendingWorkQueries;
import br.com.gestorcobranca.collection.application.port.out.DisputeMailer;
import br.com.gestorcobranca.collection.application.service.CollectionTestHooks;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactCommand;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rotina completa das contestações contra o PostgreSQL real: responsável (distribuição automática, assumir,
 * redistribuir, histórico), prazos por tipo, pedido ao credor com modelo e contatos do cadastro (e-mail capturado por
 * um dublê do SMTP), link seguro das evidências, lembrete e cobrança do retorno vencido, resposta do credor manual e
 * pela integração, pendências por responsável, resolução com ajuste sugerido detectado no título, resposta ao devedor
 * pelo porteiro com a categoria transacional e indicadores do painel.
 */
@Import(DisputeWorkflowIT.MailConfig.class)
class DisputeWorkflowIT extends CollectionTestSupport {

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        RecordingDisputeMailer recordingDisputeMailer() {
            return new RecordingDisputeMailer();
        }
    }

    /** Dublê do SMTP: guarda os e-mails "aceitos". */
    static final class RecordingDisputeMailer implements DisputeMailer {

        final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();

        @Override
        public Result send(OutgoingMail mail) {
            sent.add(mail);
            return Result.ok();
        }
    }

    private static final List<String> ALL_DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
            "SATURDAY", "SUNDAY");

    @Autowired
    RecordingDisputeMailer mailer;
    @Autowired
    DisputeConfigUseCases disputeConfig;
    @Autowired
    CreditorDisputeContactUseCases disputeContacts;
    @Autowired
    DisputeEvidenceLinkUseCases evidenceLinks;
    @Autowired
    CollectionPendingWorkQueries pendingWork;
    @Autowired
    ChannelAccountUseCases channels;
    @Autowired
    ContactPolicyUseCases policies;
    @Autowired
    DebtTitleUseCases titleUseCases;
    @Autowired
    AnalyticsQueries analytics;

    @BeforeEach
    void setUp() {
        provision("disp");
        mailer.sent.clear();
        system(() -> disputeContacts.create(creditorA, new DisputeContactCommand(null, "Equipe de contestações",
                "contestacoes@bancoa.test", null, "PRIMARY")));
        system(() -> disputeContacts.create(creditorA, new DisputeContactCommand(null, "Jurídico", "juridico@bancoa.test",
                null, "COPY")));
        // contato de cobrança limitado a 1 por dia no e-mail, janela o dia todo (a resposta transacional ignora o limite)
        system(() -> policies.publish(new PublishPolicyCommand(null, null, "teste", List.of(
                new ChannelRuleInput("EMAIL", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, 1, null, null, false),
                new ChannelRuleInput("WHATSAPP", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("PHONE", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("SMS", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false)))));
        system(() -> channels.create(new CreateAccountCommand("EMAIL", "SANDBOX", null, "E-mail sandbox",
                "cobranca@assessoria.test", "Assessoria", null, Map.of())));
    }

    private DisputeView openDispute(String type, String... principals) {
        UUID debtor = debtor(randomCpf());
        load(creditorA, portfolioA, debtor, principals.length == 0 ? new String[]{"300.00"} : principals);
        UUID caseId = activeCase(debtor, portfolioA).id();
        return asOperator(operatorX, () -> disputes.open(caseId, new OpenDisputeCommand(Set.of(), type, "DEBTOR",
                "Contestação de teste", null, null)));
    }

    private static String randomCpf() {
        // CPF válido aleatório (dígitos verificadores calculados)
        int[] d = new int[11];
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 9; i++) {
            d[i] = r.nextInt(10);
        }
        for (int j = 9; j < 11; j++) {
            int sum = 0;
            for (int i = 0; i < j; i++) {
                sum += d[i] * (j + 1 - i);
            }
            int v = 11 - sum % 11;
            d[j] = v >= 10 ? 0 : v;
        }
        StringBuilder s = new StringBuilder();
        for (int x : d) {
            s.append(x);
        }
        return s.toString();
    }

    /** Com o relógio da aplicação adiantado, os jobs nascem "no futuro" do banco: libera-os para o worker. */
    private void drainShiftedJobs() {
        jdbcTemplate.update("update shared.processing_job set run_at = now() where type = 'collection.dispute_mail' "
                + "and status = 'PENDING'");
        drainJobs();
    }

    private <T> T asWorker(UUID userId, java.util.function.Supplier<T> action) {
        return asOperator(userId, action);
    }

    @Test
    void assignmentDeadlinesAndPendingWorkFollowTheResponsible() {
        system(() -> disputeConfig.createRule(new DeadlineRuleCommand(null, "FRAUD", 15, 5)));
        asSupervisor(() -> disputeConfig.updateSettings(new DisputeSettingsCommand("LEAST_LOADED", 7, 5, 1, 7)));
        asSupervisor(() -> disputeConfig.addPoolMember(null, operatorX));
        asSupervisor(() -> disputeConfig.addPoolMember(null, operatorY));

        DisputeView fraud = openDispute("FRAUD");
        assertThat(fraud.deadlineSource()).isEqualTo("TYPE");
        assertThat(fraud.responseDueDate()).isEqualTo(LocalDate.now(ZONE).plusDays(15));
        assertThat(fraud.internalDueDate()).isEqualTo(LocalDate.now(ZONE).plusDays(5));
        assertThat(fraud.assigneeId()).isIn(operatorX, operatorY);
        DisputeView other = openDispute("OTHER");
        assertThat(other.deadlineSource()).isEqualTo("TENANT_DEFAULT");
        assertThat(other.assigneeId()).isIn(operatorX, operatorY).isNotEqualTo(fraud.assigneeId());

        // analista que não é o responsável não trabalha a contestação do outro
        UUID owner = fraud.assigneeId();
        UUID notOwner = owner.equals(operatorX) ? operatorY : operatorX;
        assertThatThrownBy(() -> asWorker(notOwner, () -> disputes.startReview(fraud.id())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("outro responsável");
        // o operador sem permissão de gestão não atribui
        assertThatThrownBy(() -> asWorker(owner, () -> disputes.assign(fraud.id(), notOwner, "x")))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // supervisor devolve à fila; o operador assume ("pegar para mim"); depois redistribui as dele
        asSupervisor(() -> disputes.assign(fraud.id(), null, "Revisar distribuição"));
        DisputeView taken = asWorker(notOwner, () -> disputes.take(fraud.id()));
        assertThat(taken.assigneeId()).isEqualTo(notOwner);
        var result = asSupervisor(() -> disputes.redistribute(notOwner, owner, "Férias"));
        assertThat(result.moved()).isGreaterThanOrEqualTo(1);
        DisputeDetail detail = asSupervisor(() -> disputes.detail(fraud.id()));
        assertThat(detail.assignments()).extracting(a -> a.mode())
                .containsSubsequence("AUTOMATIC", "RELEASE", "TAKE", "REDISTRIBUTION");
        assertThat(detail.assignments().getLast().assigneeName()).isNotBlank();
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.assigned'"))
                .isGreaterThanOrEqualTo(4);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update collection.dispute_assignment set reason = 'x' where dispute_id = ?", fraud.id())));

        // fila e pendências: "minhas" do responsável; supervisão = sem responsável ou vencidas
        var mine = asWorker(owner, () -> disputes.search(new DisputeQuery(null, null, null, null, "me", false, false,
                null, null, null, false, false, false), PageQuery.of(0, 20)));
        assertThat(mine.content()).extracting(DisputeView::id).contains(fraud.id());
        LocalDate today = LocalDate.now(ZONE);
        assertThat(system(() -> pendingWork.disputesOfAssignee(null, owner, today, today.plusDays(10))).total())
                .isGreaterThanOrEqualTo(1);
        asSupervisor(() -> disputes.assign(other.id(), null, "Sem responsável para o teste"));
        assertThat(system(() -> pendingWork.disputesForSupervision(null, today)).total()).isEqualTo(1);
        var supervision = asSupervisor(() -> disputes.search(new DisputeQuery(null, null, null, null, null, false, false,
                null, null, null, false, false, true), PageQuery.of(0, 20)));
        assertThat(supervision.content()).extracting(DisputeView::id).containsExactly(other.id());
    }

    @Test
    void creditorRequestUsesTemplateAndRegisteredContactsWithSecureLinkAndFollowUp() throws Exception {
        DisputeView dispute = openDispute("ALREADY_PAID");
        asSupervisor(() -> disputes.assign(dispute.id(), operatorX, "Analista"));
        var evidence = asWorker(operatorX, () -> disputes.attachEvidence(dispute.id(), new EvidenceUpload("comprovante.pdf",
                "application/pdf", 9, new ByteArrayInputStream("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII)))));

        CreditorRequestDraft draft = asWorker(operatorX, () -> disputes.draftCreditorRequest(dispute.id()));
        assertThat(draft.to()).containsExactly("contestacoes@bancoa.test");
        assertThat(draft.cc()).containsExactly("juridico@bancoa.test");
        assertThat(draft.replyTo()).startsWith("x-").endsWith("@teste.com");
        assertThat(draft.body()).contains("comprovante da baixa").contains("Banco A").contains("{{link_evidencias}}")
                .doesNotContain("Devedor ");
        assertThat(draft.templateVersion()).isZero();

        CreditorRequestView request = asWorker(operatorX, () -> disputes.requestCreditor(dispute.id(),
                new CreditorRequestCommand(draft.subject(), draft.body() + "\nObservação do analista.", null, Set.of(),
                        Set.of(evidence.id()), true)));
        assertThat(request.status()).isEqualTo("QUEUED");
        assertThat(request.cc()).contains("juridico@bancoa.test", draft.replyTo());
        assertThat(asWorker(operatorX, () -> disputes.get(dispute.id())).awaitingCreditorSince()).isNotNull();
        drainJobs();
        assertThat(mailer.sent).hasSize(1);
        DisputeMailer.OutgoingMail mail = mailer.sent.getFirst();
        assertThat(mail.to()).containsExactly("contestacoes@bancoa.test");
        assertThat(mail.replyTo()).isEqualTo(draft.replyTo());
        assertThat(mail.text()).contains("Observação do analista").doesNotContain("{{");
        assertThat(asWorker(operatorX, () -> disputes.creditorRequests(dispute.id())).getFirst().status()).isEqualTo("SENT");
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.creditor_request_sent'"))
                .isEqualTo(1);

        // link seguro: o token só existe no e-mail; o banco guarda o hash
        Matcher m = Pattern.compile("/api/v1/dispute-files/([A-Za-z0-9_-]+)").matcher(mail.text());
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        assertThat(count("select count(*) from collection.dispute_link_route where token_hash = ?", token)).isZero();
        var pack = evidenceLinks.open(token, "127.0.0.1");
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        evidenceLinks.write(pack, zip);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.toByteArray()))) {
            assertThat(in.getNextEntry().getName()).isEqualTo("comprovante.pdf");
        }
        assertThatThrownBy(() -> evidenceLinks.open(token + "x", "127.0.0.1")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' "
                + "and details ->> 'resource' = 'dispute_evidence_link'")).isEqualTo(1);

        // lembrete no dia anterior ao prazo e cobrança depois do prazo, uma vez cada
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(ZONE), request.requestedDueDate());
        clock.advance(Duration.ofDays(days - 1));
        assertThat(system(() -> CollectionTestHooks.followUpCreditorRequests(applicationContext))).isEqualTo(1);
        assertThat(system(() -> CollectionTestHooks.followUpCreditorRequests(applicationContext))).isZero();
        drainShiftedJobs();
        clock.advance(Duration.ofDays(2));
        assertThat(system(() -> CollectionTestHooks.followUpCreditorRequests(applicationContext))).isEqualTo(1);
        drainShiftedJobs();
        assertThat(mailer.sent).extracting(DisputeMailer.OutgoingMail::subject)
                .anyMatch(s -> s.startsWith("Lembrete:")).anyMatch(s -> s.startsWith("Retorno vencido:"));
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.creditor_request_overdue'"))
                .isEqualTo(1);
        clock.reset();

        // resposta do credor registrada pela assessoria, com documento
        var response = asWorker(operatorX, () -> disputes.registerCreditorResponse(dispute.id(),
                new CreditorResponseCommand("ACCEPTS_CLAIM", "Credor confirmou a baixa do título", null, null),
                List.of(new EvidenceUpload("baixa.pdf", "application/pdf", 9,
                        new ByteArrayInputStream("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII))))));
        assertThat(response.source()).isEqualTo("MANUAL");
        assertThat(response.requestId()).isEqualTo(request.id());
        assertThat(response.evidenceIds()).hasSize(1);
        DisputeView afterResponse = asWorker(operatorX, () -> disputes.get(dispute.id()));
        assertThat(afterResponse.awaitingCreditorSince()).isNull();
        assertThat(asWorker(operatorX, () -> disputes.creditorRequests(dispute.id())).getFirst().status())
                .isEqualTo("ANSWERED");
        assertThat(count("select count(*) from collection.dispute_creditor_response where note_encrypted like '%baixa%'"))
                .isZero();
    }

    @Test
    void integrationActorAnswersOnlyItsCreditorDisputes() {
        DisputeView dispute = openDispute("WRONG_AMOUNT");
        CreditorRequestDraft draft = asSupervisor(() -> disputes.draftCreditorRequest(dispute.id()));
        asSupervisor(() -> disputes.requestCreditor(dispute.id(), new CreditorRequestCommand(draft.subject(), draft.body(),
                null, Set.of(), Set.of(), false)));
        Actor integrationA = new Actor(Actor.ActorType.INTEGRATION, Ids.newId(), tenant, null, "integration:test",
                EnumSet.of(Permission.CASE_VIEW), CreditorScope.of(Set.of(creditorA)));
        var response = tenantExecution.callAs(integrationA, () -> disputes.registerCreditorResponse(dispute.id(),
                new CreditorResponseCommand("PARTIAL", "Juros recalculados: memória anexa no e-mail", null, null),
                List.of()));
        assertThat(response.source()).isEqualTo("PUBLIC_API");
        Actor integrationB = new Actor(Actor.ActorType.INTEGRATION, Ids.newId(), tenant, null, "integration:test",
                EnumSet.of(Permission.CASE_VIEW), CreditorScope.of(Set.of(creditorB)));
        assertThatThrownBy(() -> tenantExecution.callAs(integrationB, () -> disputes.registerCreditorResponse(dispute.id(),
                new CreditorResponseCommand("PARTIAL", "x", null, null), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void upheldDisputeSuggestsTheTitleActionUntilItIsDoneInTheDebtFlow() {
        DisputeView dispute = openDispute("FRAUD", "200.00");
        DisputeView resolved = asSupervisor(() -> disputes.resolve(dispute.id(), new ResolveCommand("UPHELD",
                "Contrato não foi assinado pelo devedor", null)));
        assertThat(resolved.adjustmentAction()).isEqualTo("CANCEL_TITLES");
        assertThat(resolved.adjustmentStatus()).isEqualTo("PENDING");
        assertThat(resolved.adjustmentBaseline()).isEqualByComparingTo("200.00");
        assertThat(resolved.debtorNoticeStatus()).isEqualTo("PENDING");
        assertThat(system(() -> pendingWork.adjustmentsPending(null, null)).total()).isEqualTo(1);
        // sem efeito financeiro automático: o título continua em aberto até o cancelamento pelo fluxo do debt
        UUID titleId = resolved.titleIds().iterator().next();
        assertThat(system(() -> titleUseCases.get(titleId)).status()).isEqualTo("OPEN");
        asManager(() -> titleUseCases.cancel(titleId, "Fraude confirmada na contestação", null));
        awaitIdle("collection.title-status");
        awaitTrue("ajuste detectado", () -> "DONE".equals(asSupervisor(() -> disputes.get(dispute.id())).adjustmentStatus()));
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.adjustment_closed'"))
                .isEqualTo(1);

        // dispensar exige motivo e só vale para pendente
        DisputeView other = openDispute("WRONG_AMOUNT");
        asSupervisor(() -> disputes.resolve(other.id(), new ResolveCommand("PARTIALLY_UPHELD", "Juros acima do contrato",
                null)));
        assertThat(asSupervisor(() -> disputes.dismissAdjustment(other.id(), "Credor corrige na próxima carga"))
                .adjustmentStatus()).isEqualTo("DISMISSED");
        assertThatThrownBy(() -> asSupervisor(() -> disputes.dismissAdjustment(other.id(), "de novo")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void debtorResponseGoesThroughTheGateAsTransactionalCommunication() {
        DisputeView dispute = openDispute("ALREADY_PAID");
        // limite diário de cobrança no e-mail já atingido e suspensão de cobrança pedida pelo credor
        system(() -> jdbcTemplate.update("""
                insert into compliance.contact_attempt (id, tenant_id, version, created_at, updated_at, debtor_id,
                    creditor_id, channel, attempted_at, origin, category)
                values (gen_random_uuid(), ?, 0, now(), now(), ?, ?, 'EMAIL', now(), 'OPERATOR', 'COLLECTION')
                """, tenant, dispute.debtorId(), creditorA));
        system(() -> restrictions.create(new CreateRestrictionCommand(dispute.debtorId(), creditorA, null,
                "CREDITOR_REQUEST", null, null, "Credor pediu suspensão", "CREDITOR", null, null)));
        assertThatThrownBy(() -> asSupervisor(() -> disputes.draftDebtorNotice(dispute.id())))
                .isInstanceOf(ConflictException.class);
        asSupervisor(() -> disputes.resolve(dispute.id(), new ResolveCommand("REJECTED",
                "Não há pagamento no extrato do credor", null)));

        DebtorNoticeDraft draft = asSupervisor(() -> disputes.draftDebtorNotice(dispute.id()));
        assertThat(draft.body()).contains("IMPROCEDENTE").contains("Não há pagamento no extrato do credor")
                .startsWith("Olá, Devedor");
        assertThat(draft.channels()).filteredOn(c -> c.channel().equals("EMAIL")).singleElement()
                .satisfies(c -> {
                    assertThat(c.allowed()).as("porteiro transacional: %s", c.reasons()).isTrue();
                    assertThat(c.maskedContact()).doesNotContain("devedor");
                });
        var notice = asSupervisor(() -> disputes.notifyDebtor(dispute.id(), new DebtorNoticeCommand("EMAIL", null,
                draft.subject(), draft.body())));
        assertThat(notice.status()).isEqualTo("QUEUED");
        JobDrain.drain(applicationContext, jdbcTemplate, "communication.");
        awaitTrue("resposta enviada", () -> "SENT".equals(asSupervisor(() -> disputes.get(dispute.id())).debtorNoticeStatus()));
        assertThat(count("""
                select count(*) from compliance.contact_attempt
                 where debtor_id = ? and channel = 'EMAIL' and category = 'TRANSACTIONAL' and reference_type = 'message'
                """, dispute.debtorId())).isEqualTo(1);
        assertThat(count("select count(*) from communication.message where reference_type = 'dispute' and reference_id = ?",
                dispute.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.debtor_notified'"))
                .isEqualTo(1);

        // opt-out do canal continua valendo: a resposta é bloqueada e registrada
        system(() -> restrictions.create(new CreateRestrictionCommand(dispute.debtorId(), null, "EMAIL", "DEBTOR_REQUEST",
                null, null, "Pediu para não receber e-mails", "DEBTOR", null, null)));
        var blocked = asSupervisor(() -> disputes.notifyDebtor(dispute.id(), new DebtorNoticeCommand("EMAIL", null,
                draft.subject(), draft.body())));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.failureDetail()).contains("RESTRICTED_CHANNEL");
        // por outro meio (carta, protocolo): registro sem mensagem
        var registered = asSupervisor(() -> disputes.notifyDebtor(dispute.id(), new DebtorNoticeCommand("OTHER", null, null,
                "Carta registrada enviada ao endereço do cadastro (AR 123)")));
        assertThat(registered.status()).isEqualTo("REGISTERED");
    }

    @Test
    void dashboardCountsVolumeDeadlineUpheldRateAndBacklog() {
        DisputeView a = openDispute("FRAUD");
        DisputeView b = openDispute("FRAUD");
        DisputeView c = openDispute("ALREADY_PAID");
        asSupervisor(() -> disputes.assign(c.id(), operatorY, "Analista"));
        asSupervisor(() -> disputes.resolve(a.id(), new ResolveCommand("UPHELD", "Fraude confirmada", "NONE")));
        asSupervisor(() -> disputes.resolve(b.id(), new ResolveCommand("REJECTED", "Contrato assinado", null)));
        LocalDate today = LocalDate.now(ZONE);
        var report = as(supervisor, DefaultRoles.SUPERVISOR, CreditorScope.ALL, () -> analytics.disputes(
                new ReportFilter(today.minusDays(1), today, Set.of(), Set.of(), Set.of(), Set.of(), Set.of())));
        assertThat(report.totals().opened()).isEqualTo(3);
        assertThat(report.totals().resolved()).isEqualTo(2);
        assertThat(report.totals().openNow()).isEqualTo(1);
        assertThat(report.totals().upheldRate().value()).isEqualByComparingTo("0.5000");
        assertThat(report.totals().withinDeadline().value()).isEqualByComparingTo("1.0000");
        assertThat(report.byType()).filteredOn(r -> r.key().equals("FRAUD")).singleElement()
                .satisfies(r -> assertThat(r.resolved()).isEqualTo(2));
        assertThat(report.byCreditor()).singleElement().satisfies(r -> assertThat(r.label()).isEqualTo("Banco A"));
        assertThat(report.backlog()).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo(operatorY.toString());
            assertThat(r.open()).isEqualTo(1);
        });
    }
}
