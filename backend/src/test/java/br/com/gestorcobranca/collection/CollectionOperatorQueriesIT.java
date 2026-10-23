package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.ContactAvailabilityQueries;
import br.com.gestorcobranca.collection.application.port.in.ContactAvailabilityQueries.ChannelAvailability;
import br.com.gestorcobranca.collection.application.port.in.ContactAvailabilityQueries.ContactAvailability;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.ScheduleTaskCommand;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.TaskView;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consultas da estação de cobrança que fecham lacunas de API das telas: "posso contatar agora?"
 * por caso, agenda com devedor, nomes de autores (identity) e contestações com títulos e autor das
 * evidências — com escopo por credor, RLS entre assessorias e autorização na camada web.
 */
class CollectionOperatorQueriesIT extends CollectionTestSupport {

    static final String PHONE = "(11) 98765-4321";

    @Autowired
    ContactAvailabilityQueries availability;
    @Autowired
    DebtorContactUseCases debtorContacts;
    @Autowired
    WebApplicationContext webApplicationContext;

    MockMvc mvc;
    UUID debtor;
    List<UUID> titles;
    CaseView caseA;

    @BeforeEach
    void setUp() {
        provision("opq");
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        debtor = debtor("543.231.948-97");
        titles = load(creditorA, portfolioA, debtor, "120.00", "80.00");
        caseA = activeCase(debtor, portfolioA);
        awaitIdle("CollectionEventListeners");
    }

    // ------------------------------------------------------------------ posso contatar agora?

    @Test
    void contactAvailabilityFollowsTheGateWithoutRecordingAttempts() {
        system(() -> restrictions.create(new CreateRestrictionCommand(debtor, creditorA, "WHATSAPP", "CREDITOR_REQUEST",
                null, null, "Credor pediu", "CREDITOR", null, null)));
        long attemptsBefore = count("select count(*) from compliance.contact_attempt");

        ContactAvailability result = asOperator(operatorX, () -> availability.check(caseA.id(), null, null));

        assertThat(result.caseId()).isEqualTo(caseA.id());
        assertThat(result.caseEligible()).isTrue();
        assertThat(result.channels()).extracting(ChannelAvailability::channel)
                .containsExactly("PHONE", "WHATSAPP", "SMS", "EMAIL");
        Map<String, ChannelAvailability> byChannel = byChannel(result);
        ChannelAvailability whatsapp = byChannel.get("WHATSAPP");
        assertThat(whatsapp.allowed()).isFalse();
        assertThat(whatsapp.reasons()).contains("RESTRICTED_CHANNEL");
        assertThat(whatsapp.nextAllowedAt()).as("restrição sem fim previsto").isNull();
        for (String channel : List.of("PHONE", "SMS", "EMAIL")) {
            assertTemporalOnly(byChannel.get(channel));
        }
        assertThat(count("select count(*) from compliance.contact_attempt")).as("consulta não registra tentativa")
                .isEqualTo(attemptsBefore);

        ContactAvailability onlyEmail = asOperator(operatorX, () -> availability.check(caseA.id(), "email", null));
        assertThat(onlyEmail.channels()).extracting(ChannelAvailability::channel).containsExactly("EMAIL");
        assertThatThrownBy(() -> asOperator(operatorX, () -> availability.check(caseA.id(), "LETTER", null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void contactAvailabilityChecksTheChosenContactValue() {
        system(() -> restrictions.create(new CreateRestrictionCommand(debtor, null, null, "DEBTOR_REQUEST", "PHONE",
                PHONE, "Número de terceiro", "DEBTOR", null, null)));
        UUID phoneId = system(() -> debtorContacts.contactsForChannel(debtor, "PHONE").getFirst().contactId());

        Map<String, ChannelAvailability> withPhone = byChannel(asOperator(operatorX,
                () -> availability.check(caseA.id(), null, phoneId)));
        for (String channel : List.of("PHONE", "WHATSAPP", "SMS")) {
            assertThat(withPhone.get(channel).contactId()).isEqualTo(phoneId);
            assertThat(withPhone.get(channel).allowed()).isFalse();
            assertThat(withPhone.get(channel).reasons()).contains("RESTRICTED_CONTACT_VALUE");
        }
        assertThat(withPhone.get("EMAIL").contactId()).isNull();
        assertTemporalOnly(withPhone.get("EMAIL"));
        // sem o contato, o canal não está restrito (a restrição é só daquele número)
        assertTemporalOnly(byChannel(asOperator(operatorX, () -> availability.check(caseA.id(), "PHONE", null))).get("PHONE"));

        assertThatThrownBy(() -> asOperator(operatorX, () -> availability.check(caseA.id(), null, Ids.newId())))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("case.contact_invalid"));
    }

    @Test
    void contactAvailabilityConsidersCaseEligibilityAndCreditorScope() {
        asOperator(operatorX, () -> disputes.open(caseA.id(), new OpenDisputeCommand(Set.of(), "NOT_RECOGNIZED",
                "OPERATOR", "Não reconhece", null, null)));
        awaitIdle("CollectionEventListeners");

        ContactAvailability held = asOperator(operatorX, () -> availability.check(caseA.id(), null, null));
        assertThat(held.caseEligible()).isFalse();
        assertThat(held.caseReasons()).contains("DISPUTE_OPEN");
        assertThat(held.channels()).allSatisfy(ch -> assertThat(ch.allowed()).isFalse());

        // operador Z só tem o credor B: o caso do credor A não existe para ele
        assertThatThrownBy(() -> asOperator(operatorZ, () -> availability.check(caseA.id(), null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void contactAvailabilityEndpointRequiresCaseWork() throws Exception {
        mvc.perform(get("/api/v1/collection/cases/{id}/contact-availability", caseA.id())
                        .with(authentication(ActorAuthentication.of(actor(operatorX, EnumSet.of(Permission.CASE_VIEW),
                                CreditorScope.ALL)))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/collection/cases/{id}/contact-availability", caseA.id()).param("channel", "PHONE")
                        .with(authentication(ActorAuthentication.of(operator(operatorX, CreditorScope.ALL)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseA.id().toString()))
                .andExpect(jsonPath("$.channels", hasSize(1)))
                .andExpect(jsonPath("$.channels[0].channel").value("PHONE"))
                .andExpect(jsonPath("$.channels[0].reasons").isArray())
                // nada da política de contato (janelas, limites) nem do valor do contato
                .andExpect(jsonPath("$.channels[0].windowStart").doesNotExist())
                .andExpect(jsonPath("$.channels[0].maxPerDay").doesNotExist())
                .andExpect(jsonPath("$.channels[0].contactValue").doesNotExist());
        mvc.perform(get("/api/v1/collection/cases/{id}/contact-availability", caseA.id())
                        .with(authentication(ActorAuthentication.of(operator(operatorZ, CreditorScope.of(Set.of(creditorB)))))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ agenda

    @Test
    void agendaBringsTheDebtorAndHidesTheNameWithoutPermission() {
        LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);
        TaskView scheduled = asOperator(operatorX, () -> tasks.schedule(caseA.id(), new ScheduleTaskCommand("CALLBACK",
                tomorrow.atTime(LocalTime.of(10, 0)), null, null)));
        assertThat(scheduled.debtorId()).isEqualTo(debtor);
        assertThat(scheduled.debtorName()).as("só a agenda traz o nome").isNull();

        PageResponse<TaskView> mine = asOperator(operatorX, () -> tasks.agenda(null, tomorrow, tomorrow, List.of(),
                PageQuery.of(0, 20)));
        assertThat(mine.content()).singleElement().satisfies(t -> {
            assertThat(t.debtorId()).isEqualTo(debtor);
            assertThat(t.debtorName()).isEqualTo("Devedor 543");
        });

        // com CASE_WORK mas sem CASE_VIEW/DEBTOR_VIEW: devedor sem nome
        PageResponse<TaskView> withoutView = tenantExecution.callAs(actor(operatorX, EnumSet.of(Permission.CASE_WORK,
                Permission.TASK_MANAGE), CreditorScope.ALL), () -> tasks.agenda(null, tomorrow, tomorrow, List.of(),
                PageQuery.of(0, 20)));
        assertThat(withoutView.content()).singleElement().satisfies(t -> {
            assertThat(t.debtorId()).isEqualTo(debtor);
            assertThat(t.debtorName()).isNull();
        });

        // supervisor restrito ao credor B vê a agenda do operador X sem as tarefas do credor A, e a
        // paginação acompanha (o filtro de escopo está na consulta)
        PageResponse<TaskView> scoped = as(supervisor, DefaultRoles.SUPERVISOR, CreditorScope.of(Set.of(creditorB)),
                () -> tasks.agenda(operatorX, tomorrow, tomorrow, List.of(), PageQuery.of(0, 20)));
        assertThat(scoped.content()).isEmpty();
        assertThat(scoped.totalElements()).isZero();
    }

    // ------------------------------------------------------------------ nomes de autores (identity)

    @Test
    void userDisplayNamesAreTenantScopedMinimalAndLimited() throws Exception {
        UUID foreignAdmin = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand("opq-x-" + UUID.randomUUID().toString().substring(0, 8), "Outra Ltda", "outra",
                        "11.444.777/0001-61", "Admin Outra", "admin@outra-" + UUID.randomUUID().toString()
                        .substring(0, 6) + ".test"))).adminUserId();
        Actor operator = operator(operatorX, CreditorScope.ALL);

        mvc.perform(get("/api/v1/users/display-names")
                        .param("ids", operatorX + "," + operatorY + "," + foreignAdmin + "," + Ids.newId())
                        .with(authentication(ActorAuthentication.of(operator))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Operador X", "Operador Y")))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].permissions").doesNotExist())
                .andExpect(jsonPath("$[0].creditorIds").doesNotExist());

        String tooMany = IntStream.range(0, 101).mapToObj(i -> Ids.newId().toString()).collect(Collectors.joining(","));
        mvc.perform(get("/api/v1/users/display-names").param("ids", tooMany)
                        .with(authentication(ActorAuthentication.of(operator))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("user.names_limit"));

        mvc.perform(get("/api/v1/users/display-names").param("ids", operatorY.toString())
                        .with(authentication(ActorAuthentication.of(actor(operatorX, EnumSet.of(Permission.TASK_MANAGE),
                                CreditorScope.ALL)))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ contestações

    @Test
    void disputeViewsCarryTitlesAndEvidenceAuthor() {
        DisputeView dispute = asOperator(operatorX, () -> disputes.open(caseA.id(), new OpenDisputeCommand(
                Set.of(titles.getFirst()), "ALREADY_PAID", "OPERATOR", "Pagou no caixa", null, null)));
        assertThat(dispute.titles()).singleElement().satisfies(t -> {
            assertThat(t.titleId()).isEqualTo(titles.getFirst());
            assertThat(t.externalId()).startsWith("T-");
            assertThat(t.installmentNumber()).isEqualTo(1);
            assertThat(t.dueDate()).isEqualTo(LocalDate.of(2026, 3, 11));
            assertThat(t.principalAmount()).isEqualByComparingTo("120.00");
        });
        EvidenceView evidence = asOperator(operatorX, () -> disputes.attachEvidence(dispute.id(), new EvidenceUpload(
                "comprovante.txt", "text/plain", 9, new ByteArrayInputStream("pago 120".getBytes(StandardCharsets.UTF_8)))));
        assertThat(evidence.createdBy()).isEqualTo(operatorX);
        assertThat(evidence.createdByName()).isEqualTo("Operador X");
        assertThat(asSupervisor(() -> disputes.listEvidence(dispute.id()))).singleElement()
                .extracting(EvidenceView::createdByName).isEqualTo("Operador X");

        assertThat(asSupervisor(() -> disputes.search(null, Set.of(), null, PageQuery.of(0, 20))).content())
                .singleElement().satisfies(d -> assertThat(d.titles()).hasSize(1));
        assertThat(asSupervisor(() -> cases.sheet(caseA.id())).openDisputes()).singleElement()
                .satisfies(d -> assertThat(d.titles()).extracting(t -> t.titleId()).containsExactly(titles.getFirst()));
        assertThatThrownBy(() -> asOperator(operatorZ, () -> disputes.get(dispute.id())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ apoio

    private Actor operator(UUID userId, CreditorScope scope) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.OPERADOR))
                .findFirst().orElseThrow().permissions();
        return actor(userId, permissions, scope);
    }

    private Actor actor(UUID userId, Set<Permission> permissions, CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "TESTE", permissions, scope);
    }

    private static Map<String, ChannelAvailability> byChannel(ContactAvailability result) {
        return result.channels().stream().collect(Collectors.toMap(ChannelAvailability::channel, c -> c));
    }

    /** Sem restrição: permitido agora ou negado só por horário/feriado, com próximo instante previsto. */
    private static void assertTemporalOnly(ChannelAvailability channel) {
        if (channel.allowed()) {
            assertThat(channel.reasons()).isEmpty();
            assertThat(channel.nextAllowedAt()).isNull();
        } else {
            assertThat(channel.reasons()).isNotEmpty().allMatch(r -> r.equals("OUTSIDE_WINDOW") || r.equals("HOLIDAY"));
            assertThat(channel.nextAllowedAt()).isNotNull();
        }
    }
}
