package br.com.gestorcobranca.legal;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmCommand;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmView;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases.AssigneeView;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases.Purpose;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.AssignCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.ReferCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usuários atribuíveis no jurídico (decisão 686): o perfil Jurídico, sem {@code USER_VIEW}, busca o advogado interno, o
 * responsável pelo prazo e o advogado a vincular ao escritório; o resultado traz só id e nome, respeita o campo e
 * nunca alcança outra assessoria.
 */
class LegalAssigneeIT extends LegalTestSupport {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Autowired
    LegalAssigneeUseCases assignees;
    @Autowired
    WebApplicationContext webContext;
    @Autowired
    JsonCodec json;

    @BeforeEach
    void setUp() {
        provision("lassign");
    }

    private MatterView refer() {
        UUID debtor = debtor("526.018.159-06");
        load(creditorA, portfolioA, debtor, new LoadedTitle("AS-1", "C-AS-1", LocalDate.of(2026, 3, 10), "1500.00"));
        CaseView c = activeCase(debtor, portfolioA);
        return asLawyer(() -> matters.refer(new ReferCommand(c.id(), Set.of(), "Indicação manual do gestor")));
    }

    private List<UUID> ids(List<AssigneeView> views) {
        return views.stream().map(AssigneeView::id).toList();
    }

    @Test
    void lawyerWithoutUserViewFindsEligibleUsersByField() throws Exception {
        assertThat(DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.JURIDICO)).findFirst()
                .orElseThrow().permissions()).contains(Permission.LEGAL_MANAGE).doesNotContain(Permission.USER_VIEW);
        MatterView matter = refer();
        allowLawFirms();
        LawFirmView firm = asManager(() -> firms.create(new LawFirmCommand("Silva & Souza Advogados", "11.444.777/0001-61",
                null, null)));

        // vincular ao escritório: o advogado parceiro (ainda sem escritório) é elegível; busca sem acento
        List<AssigneeView> candidates = asLawyer(() -> assignees.search(Purpose.FIRM_MEMBER, null, "advogado", null,
                new PageQuery(0, 20))).content();
        assertThat(ids(candidates)).containsExactly(partner);
        asManager(() -> firms.addMember(firm.id(), partner));
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.FIRM_MEMBER, null, null, null, new PageQuery(0, 50)))
                .content())).contains(lawyer).doesNotContain(partner);

        // advogado interno do caso: com LEGAL_MANAGE e acesso ao credor, nunca membro de escritório
        List<AssigneeView> internal = asLawyer(() -> assignees.search(Purpose.MATTER_RESPONSIBLE, matter.id(), "juridico",
                null, new PageQuery(0, 20))).content();
        assertThat(ids(internal)).containsExactly(lawyer);
        assertThat(internal.getFirst().name()).isEqualTo("Jurídico J");
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.MATTER_RESPONSIBLE, matter.id(), null, null,
                new PageQuery(0, 50))).content())).doesNotContain(partner);
        // nome do que já está escolhido
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.MATTER_RESPONSIBLE, matter.id(), null, Set.of(lawyer),
                new PageQuery(0, 20))).content())).containsExactly(lawyer);

        // responsável pelo prazo: o advogado parceiro só vê os colegas do próprio escritório
        asManager(() -> matters.assign(matter.id(), new AssignCommand("LAW_FIRM", partner, firm.id())));
        assertThat(ids(asPartner(() -> assignees.search(Purpose.DEADLINE_RESPONSIBLE, matter.id(), null, null,
                new PageQuery(0, 50))).content())).containsExactly(partner);
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.DEADLINE_RESPONSIBLE, matter.id(), null, null,
                new PageQuery(0, 50))).content())).contains(lawyer, partner);
        // campos de configuração da assessoria continuam exclusivos de usuários internos
        assertThatThrownBy(() -> asPartner(() -> assignees.search(Purpose.FIRM_MEMBER, null, null, null,
                new PageQuery(0, 20)))).isInstanceOf(ScopeAccessDeniedException.class);
        // sem LEGAL_MANAGE: recusado
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.AUDITOR, CreditorScope.ALL,
                () -> assignees.search(Purpose.FIRM_MEMBER, null, null, null, new PageQuery(0, 20))))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // cada consulta vai para o log de acesso, sem o texto buscado
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and actor_id = ? and details ->> 'purpose' = 'MATTER_RESPONSIBLE' and details ->> 'q' is null", lawyer))
                .isGreaterThanOrEqualTo(3);

        // pela API: só id e nome, paginado
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        Set<Permission> lawyerPermissions = DefaultRoles.forTenant().stream()
                .filter(d -> d.code().equals(DefaultRoles.JURIDICO)).findFirst().orElseThrow().permissions();
        ActorAuthentication auth = ActorAuthentication.of(new Actor(Actor.ActorType.USER, lawyer, tenant, Ids.newId(),
                DefaultRoles.JURIDICO, lawyerPermissions, CreditorScope.ALL));
        String body = mvc.perform(get("/api/v1/legal/assignable-users").param("purpose", "MATTER_RESPONSIBLE")
                        .param("matterId", matter.id().toString()).param("size", "500").with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> page = json.mapper().readValue(body, Map.class);
        assertThat(page.get("size")).isEqualTo(LegalAssigneeUseCases.MAX_PAGE_SIZE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).isNotEmpty().allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrder("id", "name"));
        assertThat(body).doesNotContain("@teste.com").doesNotContain("email").doesNotContain("status");
        // o auditor (sem LEGAL_MANAGE) é recusado pela API
        Set<Permission> auditor = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.AUDITOR))
                .findFirst().orElseThrow().permissions();
        mvc.perform(get("/api/v1/legal/assignable-users").param("purpose", "FIRM_MEMBER").with(authentication(
                ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(),
                        DefaultRoles.AUDITOR, auditor, CreditorScope.ALL))))).andExpect(status().isForbidden());

        // outra assessoria: os usuários desta nunca aparecem
        UUID lawyerA = lawyer;
        UUID partnerA = partner;
        provision("lassign2");
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.FIRM_MEMBER, null, null, null, new PageQuery(0, 50)))
                .content())).doesNotContain(lawyerA, partnerA).contains(lawyer);
    }

    @Test
    void savingRefusesWhoTheFieldListDoesNotShow() {
        MatterView matter = refer();
        UUID operatorRole = system(() -> roles.list().stream().filter(r -> r.code().equals(DefaultRoles.OPERADOR))
                .findFirst().orElseThrow().id());
        // operador com todos os credores, mas sem LEGAL_MANAGE: antes aceito no prazo e no escritório
        UUID operator = user("Operador O", "o", operatorRole, true, Set.of());
        LocalDate today = LocalDate.now(ZONE);

        // prazo: fora da lista → recusado; o advogado da lista → gravado
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.DEADLINE_RESPONSIBLE, matter.id(), null, null,
                new PageQuery(0, 50))).content())).contains(lawyer).doesNotContain(operator);
        assertThatThrownBy(() -> asLawyer(() -> deadlines.create(matter.id(), deadline(today, operator))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_deadline.responsible_invalid");
        assertThat(asLawyer(() -> deadlines.create(matter.id(), deadline(today, lawyer))).responsibleUserId())
                .isEqualTo(lawyer);

        // advogado interno do caso: o operador não aparece e não é gravado
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.MATTER_RESPONSIBLE, matter.id(), null, null,
                new PageQuery(0, 50))).content())).doesNotContain(operator);
        assertThatThrownBy(() -> asManager(() -> matters.assign(matter.id(), new AssignCommand("INTERNAL", operator,
                null)))).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("legal_matter.responsible_invalid");

        // vínculo ao escritório: sem LEGAL_MANAGE → recusado (a lista FIRM_MEMBER não o mostra)
        allowLawFirms();
        LawFirmView firm = asManager(() -> firms.create(new LawFirmCommand("Lima Advogados", "11.444.777/0001-61",
                null, null)));
        assertThat(ids(asLawyer(() -> assignees.search(Purpose.FIRM_MEMBER, null, null, null, new PageQuery(0, 50)))
                .content())).doesNotContain(operator).contains(partner);
        assertThatThrownBy(() -> asManager(() -> firms.addMember(firm.id(), operator)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("law_firm.user_invalid");
        asManager(() -> firms.addMember(firm.id(), partner));

        // advogado parceiro: prazo só para colegas do escritório (o advogado interno some da lista e é recusado)
        asManager(() -> matters.assign(matter.id(), new AssignCommand("LAW_FIRM", partner, firm.id())));
        assertThat(ids(asPartner(() -> assignees.search(Purpose.DEADLINE_RESPONSIBLE, matter.id(), null, null,
                new PageQuery(0, 50))).content())).containsExactly(partner);
        assertThatThrownBy(() -> asPartner(() -> deadlines.create(matter.id(), deadline(today, lawyer))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_deadline.responsible_invalid");
        assertThat(asPartner(() -> deadlines.create(matter.id(), deadline(today, partner))).responsibleUserId())
                .isEqualTo(partner);
    }

    private static DeadlineCommand deadline(LocalDate start, UUID responsible) {
        return new DeadlineCommand("RESPONSE", "Manifestação", null, null, start, "INTIMATION", 10, "BUSINESS_DAYS",
                null, responsible);
    }
}
