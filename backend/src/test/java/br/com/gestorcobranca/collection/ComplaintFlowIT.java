package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintQuery;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.CreditorIndicator;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.EvidenceUpload;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.RegisterComplaintCommand;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reclamações (I5): registro pela operação com vínculo ao caso, escopo por credor, prazo por canal (perto do prazo e
 * vencida), "Minhas pendências", tratamento pela supervisão (responsável, resposta no prazo ou fora, encerramento),
 * evidência em quarentena, minimização do texto (criptografado, detalhe com log de acesso) e indicador por credor.
 */
class ComplaintFlowIT extends CollectionTestSupport {

    @Autowired
    ComplaintUseCases complaints;
    @Autowired
    PendingWorkUseCases pendingWork;

    UUID debtor;
    UUID caseId;

    @BeforeEach
    void setUp() {
        provision("recl");
        debtor = debtor("52998224725");
        load(creditorA, portfolioA, debtor, "900.00");
        caseId = activeCase(debtor, portfolioA).id();
    }

    @Test
    void complaintLifecycleWithDeadlinesScopeAndIndicator() {
        Instant now = Instant.now();
        ComplaintView procon = asOperator(operatorX, () -> complaints.register(new RegisterComplaintCommand(creditorA,
                null, caseId, "PROCON", "PROCON-2026-0001", "ABUSIVE_COLLECTION",
                "Consumidor relata ligações insistentes para o trabalho", now, null, null)));
        assertThat(procon.debtorId()).isEqualTo(debtor);
        assertThat(procon.status()).isEqualTo("RECEIVED");
        assertThat(procon.deadlineSource()).isEqualTo("DEFAULT");
        assertThat(procon.dueDate()).isEqualTo(LocalDate.now(ZONE).plusDays(10));
        assertThat(procon.deadlineState()).isEqualTo("ON_TIME");
        // texto livre só criptografado; protocolo do canal único por canal
        assertThat(count("select count(*) from complaint.complaint where summary_encrypted like '%insistentes%'"))
                .isZero();
        assertThatThrownBy(() -> asOperator(operatorX, () -> complaints.register(new RegisterComplaintCommand(creditorA,
                null, null, "PROCON", "PROCON-2026-0001", "OTHER", "Duplicada", now, null, null))))
                .isInstanceOf(ConflictException.class);
        // caso de outro credor não pode ser vinculado
        assertThatThrownBy(() -> asOperator(operatorX, () -> complaints.register(new RegisterComplaintCommand(creditorB,
                null, caseId, "OTHER", null, "OTHER", "Caso errado", now, null, null))))
                .isInstanceOf(BusinessRuleException.class);

        // escopo: operador restrito ao credor B não vê nem abre
        assertThat(asOperator(operatorZ, () -> complaints.search(query(null), PageQuery.of(0, 10))).content()).isEmpty();
        assertThatThrownBy(() -> asOperator(operatorZ, () -> complaints.detail(procon.id())))
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
        // operação registra, mas não trata
        assertThatThrownBy(() -> asOperator(operatorX, () -> complaints.answer(procon.id(), "Resposta", null)))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // prazo por canal: consumidor.gov.br em 10 dias com aviso de 3; recebida há 8 dias → perto do prazo
        asSupervisor(() -> complaints.updateDeadlineRule("CONSUMIDOR_GOV", 10, 3));
        ComplaintView soon = asOperator(operatorX, () -> complaints.register(new RegisterComplaintCommand(creditorA,
                debtor, null, "CONSUMIDOR_GOV", "CG-77", "WRONG_AMOUNT", "Valor cobrado diferente do contrato",
                now.minus(Duration.ofDays(8)), null, null)));
        assertThat(soon.deadlineSource()).isEqualTo("CHANNEL_RULE");
        assertThat(soon.deadlineState()).isEqualTo("DUE_SOON");
        // vencida: Reclame Aqui recebida há 15 dias (padrão de 10)
        ComplaintView late = asOperator(operatorX, () -> complaints.register(new RegisterComplaintCommand(creditorA,
                debtor, null, "RECLAME_AQUI", null, "CONTACT_FREQUENCY", "Muitas mensagens por dia",
                now.minus(Duration.ofDays(15)), null, null)));
        assertThat(late.deadlineState()).isEqualTo("OVERDUE");
        assertThat(asSupervisor(() -> complaints.search(query("overdue"), PageQuery.of(0, 10))).content())
                .extracting(ComplaintView::id).containsExactly(late.id());
        assertThat(asSupervisor(() -> complaints.search(query("soon"), PageQuery.of(0, 10))).content())
                .extracting(ComplaintView::id).containsExactlyInAnyOrder(late.id(), soon.id());

        // "Minhas pendências" da supervisão: sem responsável perto do prazo (inclui a vencida)
        var summary = asSupervisor(() -> pendingWork.mine());
        assertThat(summary.items()).anySatisfy(i -> {
            assertThat(i.type()).isEqualTo("complaint.unassigned_due");
            assertThat(i.count()).isEqualTo(2);
            assertThat(i.overdue()).isEqualTo(1);
        });

        // evidência: quarentena e verificação antes de liberar
        byte[] pdf = "%PDF-1.4\n% notificação fictícia\n".getBytes(StandardCharsets.UTF_8);
        var evidence = asOperator(operatorX, () -> complaints.attachEvidence(procon.id(), new EvidenceUpload(
                "notificacao.pdf", "application/pdf", pdf.length, new ByteArrayInputStream(pdf))));
        assertThat(evidence.status()).isEqualTo("PENDING_SCAN");
        JobDrain.drain(applicationContext, jdbcTemplate, "complaint.");
        assertThat(asOperator(operatorX, () -> complaints.evidences(procon.id()))).singleElement()
                .satisfies(e -> assertThat(e.status()).isEqualTo("AVAILABLE"));

        // supervisão trata: começa (assume), responde no prazo e encerra
        ComplaintView started = asSupervisor(() -> complaints.start(procon.id()));
        assertThat(started.assigneeId()).isEqualTo(supervisor);
        asSupervisor(() -> complaints.answer(procon.id(), "Contatos suspensos e operador orientado", null));
        ComplaintView closed = asSupervisor(() -> complaints.close(procon.id(), "RESOLVED"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.answeredOnTime()).isTrue();
        // fora do prazo
        asSupervisor(() -> complaints.answer(late.id(), "Frequência ajustada", null));

        // detalhe com o texto, registrando o acesso a dado pessoal
        var detail = asSupervisor(() -> complaints.detail(procon.id()));
        assertThat(detail.summary()).contains("insistentes");
        assertThat(count("select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED' "
                + "and details ->> 'resource' = 'complaint' and details ->> 'resourceId' = ?", procon.id().toString()))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action in "
                + "('complaint.registered', 'complaint.started', 'complaint.answered', 'complaint.closed')", procon.id()))
                .isEqualTo(4);
        assertThat(count("select count(*) from shared.business_event where event_type = 'complaint.answered' "
                + "and attributes::text like '%insistentes%'")).isZero();

        // indicador por credor
        List<CreditorIndicator> indicator = asSupervisor(() -> complaints.indicatorByCreditor(
                LocalDate.now(ZONE).minusDays(30), LocalDate.now(ZONE)));
        assertThat(indicator).singleElement().satisfies(i -> {
            assertThat(i.creditorId()).isEqualTo(creditorA);
            assertThat(i.received()).isEqualTo(3);
            assertThat(i.answeredOnTime()).isEqualTo(1);
            assertThat(i.answeredLate()).isEqualTo(1);
            assertThat(i.open()).isEqualTo(1);
            assertThat(i.openOverdue()).isZero();
        });
    }

    @Test
    void scopeUnlinkAndAnonymization() {
        // comando para credor fora do escopo: 403 (não 404)
        assertThatThrownBy(() -> asOperator(operatorZ, () -> complaints.register(new RegisterComplaintCommand(creditorA,
                null, null, "PROCON", null, "OTHER", "Fora do escopo", Instant.now(), null, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // regra por canal vale para todos os credores: supervisor restrito não altera (403)
        assertThatThrownBy(() -> as(supervisor, br.com.gestorcobranca.identity.domain.model.DefaultRoles.SUPERVISOR,
                br.com.gestorcobranca.shared.security.CreditorScope.of(Set.of(creditorA)),
                () -> complaints.updateDeadlineRule("PROCON", 5, 1))).isInstanceOf(ScopeAccessDeniedException.class);

        ComplaintView c = asOperator(operatorX, () -> complaints.register(new RegisterComplaintCommand(creditorA, debtor,
                null, "BACEN", "RDR-1", "DATA_PRIVACY", "Relato fictício do titular", Instant.now(), null, null)));
        // não se desvincula o devedor (sairia do alcance da anonimização)
        assertThatThrownBy(() -> asOperator(operatorX, () -> complaints.reclassify(c.id(),
                new ComplaintUseCases.ReclassifyCommand(null, null, "RDR-1", "DATA_PRIVACY"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("desvincular");
        byte[] pdf = "%PDF-1.4\n% fictício\n".getBytes(StandardCharsets.UTF_8);
        asOperator(operatorX, () -> complaints.attachEvidence(c.id(), new EvidenceUpload("n.pdf", "application/pdf",
                pdf.length, new ByteArrayInputStream(pdf))));
        JobDrain.drain(applicationContext, jdbcTemplate, "complaint.");
        assertThat(count("select count(*) from complaint.complaint_evidence where complaint_id = ? "
                + "and status = 'AVAILABLE' and file_key is not null", c.id())).isEqualTo(1);

        publishAndAwait(new br.com.gestorcobranca.shared.events.debt.DebtorAnonymized(
                br.com.gestorcobranca.shared.domain.Ids.newId(), tenant, Instant.now(), debtor),
                "complaint.debtor-anonymized");
        assertThat(asSupervisor(() -> complaints.detail(c.id())).summary()).contains("removidos");
        assertThat(asSupervisor(() -> complaints.detail(c.id())).complaint().externalProtocol()).isNull();
        assertThat(count("select count(*) from complaint.complaint_evidence where complaint_id = ? "
                + "and status = 'REMOVED' and file_key is null", c.id())).isEqualTo(1);
    }

    private static ComplaintQuery query(String deadline) {
        return new ComplaintQuery(null, Set.of(), Set.of(), null, null, null, false, deadline, null);
    }
}
