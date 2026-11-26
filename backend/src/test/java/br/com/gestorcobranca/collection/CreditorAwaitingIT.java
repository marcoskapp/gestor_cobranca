package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.RespondCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.Awaiting;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.AwaitingItem;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.support.JsonContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Aguardando você" do portal do credor (fatia 7; RS-63): as contestações contam pelo mesmo filtro da lista
 * "Aguardando você" — sem pedido ao credor não conta, a de outro credor não aparece, some depois da resposta —, com
 * prazo, "vence logo" e vencidas; demonstrativos enviados (inclusive sem prazo, que nunca vence) e informes de
 * pagamento recusados entram junto; quem não responde vê o item sem poder agir; o contrato da resposta por snapshot.
 */
@Import(DisputeWorkflowIT.MailConfig.class)
class CreditorAwaitingIT extends CollectionTestSupport {

    private static final Set<Permission> READ_ONLY = Set.of(Permission.CREDITOR_PORTAL_OVERVIEW);
    private static final Set<Permission> LEGAL = Set.of(Permission.CREDITOR_PORTAL_OVERVIEW,
            Permission.CREDITOR_DISPUTE_RESPOND);

    @Autowired
    CreditorDisputeContactUseCases disputeContacts;
    @Autowired
    CreditorDisputePortalUseCases portal;
    @Autowired
    CreditorHomeUseCases home;
    @Autowired
    CreditorDisputeUseCases creditorDisputes;
    @Autowired
    ShiftableClock clock;
    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;

    @Test
    void contaPeloFiltroDaListaSomeDepoisDaRespostaEMostraOPrazo() throws Exception {
        provision("crawait");
        system(() -> disputeContacts.create(creditorA, new DisputeContactCommand(null, "Equipe de contestações",
                "contestacoes@bancoa.test", null, "PRIMARY")));
        system(() -> disputeContacts.create(creditorB, new DisputeContactCommand(null, "Jurídico",
                "juridico@lojab.test", null, "PRIMARY")));
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        DisputeView soon = openDispute(creditorA);
        DisputeView later = openDispute(creditorA);
        DisputeView withoutRequest = openDispute(creditorA);
        DisputeView otherCreditor = openDispute(creditorB);
        CreditorRequestView soonRequest = request(soon, today.plusDays(1));
        request(later, today.plusDays(10));
        request(otherCreditor, today.plusDays(1));
        drainJobs();

        // sem pedido não conta; a de outro credor não aparece; pelo prazo, a que vence logo primeiro
        Awaiting a = asCreditor(creditorA, READ_ONLY, home::awaiting);
        assertThat(a.unavailable()).isEmpty();
        assertThat(a.total()).isEqualTo(2);
        assertThat(a.counts().disputes()).isEqualTo(2);
        assertThat(a.dueSoon()).isEqualTo(1);
        assertThat(a.overdue()).isZero();
        assertThat(a.items()).extracting(AwaitingItem::id).containsExactly(soon.id(), later.id())
                .doesNotContain(withoutRequest.id(), otherCreditor.id());
        assertThat(a.items().getFirst()).satisfies(i -> {
            assertThat(i.kind()).isEqualTo("DISPUTE");
            assertThat(i.dueOn()).isEqualTo(today.plusDays(1));
            assertThat(i.dueSoon()).isTrue();
            assertThat(i.youCanAct()).isFalse();
            assertThat(i.dispute().protocol()).hasSize(8);
            assertThat(i.dispute().type()).isEqualTo("ALREADY_PAID");
            assertThat(i.statement()).isNull();
            assertThat(i.payment()).isNull();
        });
        // o mesmo número da lista "Aguardando você" (RS-63)
        assertThat(asCreditor(creditorA, READ_ONLY, portal::disputes))
                .filteredOn(d -> d.situation().equals("AWAITING_YOU")).hasSize(a.total());
        assertThat(asCreditor(creditorA, LEGAL, home::awaiting).items()).allMatch(AwaitingItem::youCanAct);
        // a porta do credor não atende a assessoria
        assertThatThrownBy(() -> asSupervisor(() -> creditorDisputes.awaiting(5)))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // demonstrativos enviados (um com prazo, um "SENT sem prazo", enviado pela versão anterior durante o expand da
        // V0902) e um informe de pagamento recusado: entram no "Aguardando você" ao lado das contestações
        UUID dueStatement = sentStatement(1, today.plusDays(5));
        UUID noDueStatement = sentStatement(2, null);
        UUID rejectedPayment = rejectedPortalPayment();
        Awaiting mixed = asCreditor(creditorA, READ_ONLY, home::awaiting);
        assertThat(mixed.unavailable()).isEmpty();
        assertThat(mixed.total()).isEqualTo(5);
        assertThat(mixed.counts().statements()).isEqualTo(2);
        assertThat(mixed.counts().rejectedPayments()).isEqualTo(1);
        assertThat(mixed.dueSoon()).isEqualTo(1);
        assertThat(mixed.overdue()).isZero();
        // pelo prazo; sem prazo por último
        assertThat(mixed.items()).extracting(AwaitingItem::id)
                .containsExactly(soon.id(), dueStatement, later.id(), rejectedPayment, noDueStatement);
        assertThat(mixed.items()).filteredOn(i -> i.id().equals(dueStatement)).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo("STATEMENT");
            assertThat(i.dueOn()).isEqualTo(today.plusDays(5));
            assertThat(i.statement().sequenceNumber()).isEqualTo(1);
        });
        assertThat(mixed.items()).filteredOn(i -> i.id().equals(noDueStatement)).singleElement().satisfies(i -> {
            assertThat(i.dueOn()).isNull();
            assertThat(i.overdue()).isFalse();
            assertThat(i.dueSoon()).isFalse();
        });
        assertThat(mixed.items()).filteredOn(i -> i.id().equals(rejectedPayment)).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo("PAYMENT_REJECTED");
            assertThat(i.payment().rejectionReason()).isEqualTo("PROOF_INVALID");
            assertThat(i.payment().amount()).isEqualByComparingTo("123.45");
        });
        // nada disso aparece para o credor B
        assertThat(asCreditor(creditorB, READ_ONLY, home::awaiting).items())
                .noneMatch(i -> i.id().equals(dueStatement) || i.id().equals(noDueStatement)
                        || i.id().equals(rejectedPayment));

        // HTTP: no-store e contrato por snapshot (com as seções de contestação, demonstrativo e pagamento)
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ActorAuthentication auth = ActorAuthentication.of(Actor.creditor(tenant, Ids.newId(), Ids.newId(), creditorA,
                READ_ONLY));
        String body = mvc.perform(get("/api/creditor/v1/awaiting").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(otherCreditor.id().toString()).doesNotContain("Relato do devedor");
        JsonContract.assertMatches(json.readTree(body), "creditor-portal/awaiting.txt");

        // vencida: o prazo passou e continua aguardando (o demonstrativo sem prazo nunca vence)
        clock.advance(Duration.ofDays(3));
        try {
            Awaiting overdue = asCreditor(creditorA, READ_ONLY, home::awaiting);
            assertThat(overdue.overdue()).isEqualTo(1);
            assertThat(overdue.items()).filteredOn(i -> i.id().equals(noDueStatement)).singleElement()
                    .satisfies(i -> assertThat(i.overdue()).isFalse());
            assertThat(overdue.items().getFirst()).satisfies(i -> {
                assertThat(i.id()).isEqualTo(soon.id());
                assertThat(i.overdue()).isTrue();
                assertThat(i.dueSoon()).isFalse();
            });
        } finally {
            clock.reset();
        }

        // respondida: some do "Aguardando você"
        asCreditor(creditorA, LEGAL, () -> portal.respond(soon.id(), new RespondCommand(soonRequest.id(),
                "CONFIRMS_DEBT", "A dívida é devida"), List.of()));
        Awaiting after = asCreditor(creditorA, READ_ONLY, home::awaiting);
        assertThat(after.total()).isEqualTo(4);
        assertThat(after.counts().disputes()).isEqualTo(1);
        assertThat(after.items()).filteredOn(i -> i.kind().equals("DISPUTE")).extracting(AwaitingItem::id)
                .containsExactly(later.id());
    }

    /**
     * Demonstrativo do credor A já enviado e sem resposta, gravado direto no banco (o fluxo de cálculo, aprovação e
     * envio é coberto no módulo settlement); {@code dueOn} nulo = enviado sem prazo.
     */
    private UUID sentStatement(int sequence, LocalDate dueOn) {
        UUID id = Ids.newId();
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant());
        LocalDate start = LocalDate.now(clock.withZone(ZONE)).minusMonths(sequence + 1L).withDayOfMonth(1);
        system(() -> jdbcTemplate.update("""
                insert into settlement.statement (id, tenant_id, version, created_at, updated_at, creditor_id,
                    sequence_number, period_start, period_end, status, received_agency_amount,
                    received_creditor_amount, reversed_amount, recovered_amount, reconciled_amount, agency_honorarium,
                    creditor_honorarium, commission_amount, fixed_fee_amount, expenses_amount, adjustments_amount,
                    net_transfer_amount, transferred_amount, negotiated_amount, forecast_commission,
                    pending_reconciliation_amount, pending_rules_amount, open_divergences, receipts_count,
                    calculation_schema, calculated_at, calculated_by, reviewed_at, reviewed_by, approved_at,
                    approved_by, sent_at, sent_by, sent_channel, response_due_on)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, 'SENT', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    1, ?, ?, ?, ?, ?, ?, ?, ?, 'PORTAL', ?)""",
                id, tenant, now, now, creditorA, sequence, start, start.plusMonths(1).minusDays(1), now, Ids.newId(),
                now, Ids.newId(), now, Ids.newId(), now, Ids.newId(), dueOn));
        return id;
    }

    /** Informe de pagamento do portal do credor A recusado agora (o fluxo de informe e revisão está no payment). */
    private UUID rejectedPortalPayment() {
        UUID id = Ids.newId();
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant());
        UUID person = Ids.newId();
        system(() -> jdbcTemplate.update("""
                insert into payment.payment (id, tenant_id, version, created_at, updated_at, source, method, status,
                    creditor_id, debtor_id, amount, paid_at, external_origin, informed_by, informed_at,
                    review_required, review_mode, review_reason, reviewed_by, reviewed_at, allocated_amount,
                    evidence_key, evidence_name, evidence_content_type, evidence_size, evidence_sha256,
                    informed_channel, creditor_title_numbers, evidence_scan_verdict, evidence_scanned_at,
                    creditor_rejection_reason)
                values (?, ?, 0, ?, ?, 'EXTERNAL', 'PIX', 'REJECTED', ?, ?, 123.45, ?, 'CREDITOR_DIRECT', ?, ?,
                    true, 'DUAL_CONTROL', 'CREDITOR_PORTAL', ?, ?, 0, 'teste/comprovante.pdf', 'comprovante.pdf',
                    'application/pdf', 10, ?, 'CREDITOR_PORTAL', 'T-1', 'CLEAN', ?, 'PROOF_INVALID')""",
                id, tenant, now, now, creditorA, Ids.newId(), now, person, now, Ids.newId(), now, "a".repeat(64), now));
        return id;
    }

    private CreditorRequestView request(DisputeView dispute, LocalDate due) {
        asSupervisor(() -> disputes.assign(dispute.id(), operatorX, "Analista"));
        CreditorRequestDraft draft = asOperator(operatorX, () -> disputes.draftCreditorRequest(dispute.id()));
        return asOperator(operatorX, () -> disputes.requestCreditor(dispute.id(),
                new CreditorRequestCommand(draft.subject(), draft.body(), due, Set.of(), Set.of(), false)));
    }

    private DisputeView openDispute(UUID creditorId) {
        UUID debtor = debtor(randomCpf());
        load(creditorId, creditorId.equals(creditorA) ? portfolioA : portfolioB, debtor, "300.00");
        UUID caseId = activeCase(debtor, creditorId.equals(creditorA) ? portfolioA : portfolioB).id();
        return asOperator(operatorX, () -> disputes.open(caseId, new OpenDisputeCommand(Set.of(), "ALREADY_PAID",
                "DEBTOR", "Relato do devedor que o credor não vê", null, null)));
    }

    private <T> T asCreditor(UUID creditorId, Set<Permission> permissions, Supplier<T> action) {
        return tenantExecution.callAs(Actor.creditor(tenant, Ids.newId(), Ids.newId(), creditorId, permissions), action);
    }

    private static String randomCpf() {
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
}
