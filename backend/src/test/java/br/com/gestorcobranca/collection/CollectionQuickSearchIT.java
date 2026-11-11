package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeQuery;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.collection.application.port.in.QuickSearchQueries;
import br.com.gestorcobranca.collection.application.port.in.QuickSearchQueries.QuickSearchView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Busca rápida de devedor e caso (Ctrl+K, revisão de produto 11/2026, item 1.3): critérios (nome, CPF/CNPJ e telefone
 * completos, código do título), escopo por credor, casos só com {@code CASE_VIEW}, log de acesso a dado pessoal sem o
 * termo buscado e o termo fora da URL.
 */
class CollectionQuickSearchIT extends CollectionTestSupport {

    private static final String CPF_A = "526.018.159-06";
    private static final String CPF_B = "083.016.613-05";
    /** Os dois devedores de teste têm este celular (`CollectionTestSupport#debtor`). */
    private static final String PHONE = "(11) 98765-4321";

    @Autowired
    QuickSearchQueries quickSearch;
    @Autowired
    WebApplicationContext webApplicationContext;

    MockMvc mvc;
    UUID debtorA;
    UUID debtorB;
    CaseView caseA;
    String titleCodeA;

    @BeforeEach
    void setUp() {
        provision("qks");
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        debtorA = debtor(CPF_A);
        List<UUID> titles = load(creditorA, portfolioA, debtorA, "300.00");
        debtorB = debtor(CPF_B);
        load(creditorB, portfolioB, debtorB, "120.00");
        awaitIdle("CollectionEventListeners");
        caseA = activeCase(debtorA, portfolioA);
        titleCodeA = system(() -> jdbcTemplate.queryForObject("select external_id from debt.debt_title where id = ?",
                String.class, titles.getFirst()));
    }

    @Test
    void findsDebtorAndCaseByCompleteDocumentAndLogsTheSearchWithoutTheTerm() {
        QuickSearchView view = asOperator(operatorX, () -> quickSearch.search(CPF_A));

        assertThat(view.criteria()).containsExactly("DOCUMENT");
        assertThat(view.debtors()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(debtorA);
            assertThat(d.matchedBy()).isEqualTo("DOCUMENT");
            assertThat(d.maskedDocument()).isEqualTo("***.018.159-**");
            assertThat(d.creditorIds()).containsExactly(creditorA);
        });
        assertThat(view.cases()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(caseA.id());
            assertThat(c.creditorId()).isEqualTo(creditorA);
        });
        assertThat(view.creditorNames()).containsEntry(creditorA, "Banco A").doesNotContainKey(creditorB);
        assertThat(view.portfolioNames()).containsEntry(portfolioA, "Carteira A-01");

        assertThat(count("select count(*) from shared.access_log where event_type = 'PERSONAL_DATA_SEARCHED' "
                + "and actor_id = ? and details ->> 'criteria' = 'DOCUMENT' and details ->> 'returned' = '1' "
                + "and details ->> 'resourceIds' = ?", operatorX, debtorA.toString())).isEqualTo(1);
        assertThat(count("select count(*) from shared.access_log where details::text like '%52601815906%' "
                + "or details::text like '%018.159%'")).as("o documento buscado nunca é gravado").isZero();
    }

    @Test
    void respectsTheCreditorScopeWithoutRevealingExistence() {
        // operador Z só tem o credor B: o devedor A não aparece, mas a tentativa fica no log
        QuickSearchView outOfScope = asOperator(operatorZ, () -> quickSearch.search(CPF_A));
        assertThat(outOfScope.debtors()).isEmpty();
        assertThat(outOfScope.cases()).isEmpty();
        assertThat(count("select count(*) from shared.access_log where event_type = 'PERSONAL_DATA_SEARCHED' "
                + "and actor_id = ? and details ->> 'returned' = '0' and details ->> 'resourceIds' is null",
                operatorZ)).isEqualTo(1);

        // pelo telefone (comum aos dois devedores), só o devedor do credor B
        QuickSearchView byPhone = asOperator(operatorZ, () -> quickSearch.search(PHONE));
        assertThat(byPhone.criteria()).containsExactly("PHONE");
        assertThat(byPhone.debtors()).extracting(QuickSearchQueries.DebtorResult::id).containsExactly(debtorB);
        assertThat(byPhone.cases()).allSatisfy(c -> assertThat(c.creditorId()).isEqualTo(creditorB));

        // com todos os credores, os dois
        assertThat(asOperator(operatorX, () -> quickSearch.search(PHONE)).debtors())
                .extracting(QuickSearchQueries.DebtorResult::id).containsExactlyInAnyOrder(debtorA, debtorB);
        // nome e código fora do escopo também não aparecem
        assertThat(asOperator(operatorZ, () -> quickSearch.search(titleCodeA)).debtors()).isEmpty();
        assertThat(asOperator(operatorZ, () -> quickSearch.search("Devedor 526")).debtors()).isEmpty();
    }

    @Test
    void findsByNameAndTitleCodeWithoutAccessLogAndIgnoresPartialPersonalData() {
        long before = count("select count(*) from shared.access_log where event_type = 'PERSONAL_DATA_SEARCHED'");

        QuickSearchView byName = asOperator(operatorX, () -> quickSearch.search("devedor 526"));
        assertThat(byName.criteria()).containsExactly("NAME");
        assertThat(byName.debtors()).extracting(QuickSearchQueries.DebtorResult::id).containsExactly(debtorA);

        QuickSearchView byCode = asOperator(operatorX, () -> quickSearch.search(titleCodeA));
        assertThat(byCode.criteria()).contains("TITLE_CODE");
        assertThat(byCode.debtors()).first().satisfies(d -> {
            assertThat(d.id()).isEqualTo(debtorA);
            assertThat(d.matchedBy()).isEqualTo("TITLE_CODE");
        });
        assertThat(byCode.cases()).extracting(QuickSearchQueries.CaseResult::id).containsExactly(caseA.id());

        // trecho de documento não busca por documento (nem por nome) e nada vai ao log de acesso
        QuickSearchView partial = asOperator(operatorX, () -> quickSearch.search("526.018"));
        assertThat(partial.criteria()).containsExactly("TITLE_CODE");
        assertThat(partial.debtors()).isEmpty();
        assertThat(asOperator(operatorX, () -> quickSearch.search("De")).criteria()).isEmpty();

        assertThat(count("select count(*) from shared.access_log where event_type = 'PERSONAL_DATA_SEARCHED'"))
                .isEqualTo(before);
    }

    @Test
    void phoneCandidatesAreLimitedInsideTheScopeOnly() {
        // 51 devedores sem título no credor B com o mesmo celular, criados antes do devedor do operador Z
        for (int i = 0; i < 51; i++) {
            debtor(cpf(100_000_000 + i));
        }
        UUID mine = debtor(cpf(200_000_000));
        load(creditorB, portfolioB, mine, "80.00");

        QuickSearchView view = asOperator(operatorZ, () -> quickSearch.search(PHONE));
        assertThat(view.debtors()).extracting(QuickSearchQueries.DebtorResult::id).containsExactly(debtorB, mine);
        assertThat(view.truncated()).as("os de fora do escopo não contam para o \"há mais\"").isFalse();
        // com todos os credores, o excedente aparece como "há mais resultados"
        assertThat(asOperator(operatorX, () -> quickSearch.search(PHONE)).truncated()).isTrue();
    }

    @Test
    void documentOutOfScopeStillSearchesThePhone() {
        // 11 dígitos que são, ao mesmo tempo, o CPF de um devedor do credor A e o celular de um devedor do credor B
        String digits = cpf(119_876_543);
        UUID outside = debtor(digits);
        load(creditorA, portfolioA, outside, "50.00");
        UUID visible = system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf(300_000_000), "Devedor Visível",
                LocalDate.of(1990, 1, 1), List.of(new ContactRecord("PHONE", digits, "MOBILE", true)))).id());
        load(creditorB, portfolioB, visible, "60.00");

        QuickSearchView view = asOperator(operatorZ, () -> quickSearch.search(digits));
        assertThat(view.criteria()).containsExactly("DOCUMENT", "PHONE");
        assertThat(view.debtors()).extracting(QuickSearchQueries.DebtorResult::id).containsExactly(visible);
        // quem vê o credor A acha pelo documento e o telefone não é buscado
        assertThat(asOperator(operatorX, () -> quickSearch.search(digits)).debtors())
                .extracting(QuickSearchQueries.DebtorResult::id).containsExactly(outside);
    }

    @Test
    void debtorInteractionsAndDisputesComeInOneQueryWithinTheScope() throws Exception {
        // o mesmo devedor também no credor B: dois casos, um de cada credor
        load(creditorB, portfolioB, debtorA, "90.00");
        awaitIdle("CollectionEventListeners");
        CaseView caseB = activeCase(debtorA, portfolioB);
        record(operatorX, caseA.id(), interaction("NO_ANSWER", null, null, null));
        record(operatorX, caseB.id(), interaction("NO_ANSWER", null, null, null));
        asOperator(operatorX, () -> disputes.open(caseA.id(), new OpenDisputeCommand(Set.of(), "ALREADY_PAID", "OPERATOR",
                "Contestação de teste", null, null)));
        asOperator(operatorX, () -> disputes.open(caseB.id(), new OpenDisputeCommand(Set.of(), "ALREADY_PAID", "OPERATOR",
                "Contestação de teste", null, null)));

        var all = asOperator(operatorX, () -> interactions.listByDebtor(debtorA, new PageQuery(0, 50)));
        assertThat(all.content()).extracting(i -> i.caseId()).containsExactly(caseB.id(), caseA.id());
        var onlyB = asOperator(operatorZ, () -> interactions.listByDebtor(debtorA, new PageQuery(0, 50)));
        assertThat(onlyB.content()).extracting(i -> i.creditorId()).containsOnly(creditorB);
        assertThat(onlyB.totalElements()).isEqualTo(1);
        // a interação pelo id (link da monitoria) segue o escopo do caso dela: fora dele, nem a existência aparece
        UUID inA = all.content().get(1).id();
        assertThat(asOperator(operatorX, () -> interactions.locate(inA)).caseId()).isEqualTo(caseA.id());
        assertThatThrownBy(() -> asOperator(operatorZ, () -> interactions.locate(inA)))
                .isInstanceOf(ResourceNotFoundException.class);

        var disputesB = asOperator(operatorZ, () -> disputes.search(new DisputeQuery(null, null, null, null, null, false,
                false, null, null, null, false, false, false, false, debtorA), new PageQuery(0, 20)));
        assertThat(disputesB.content()).extracting(d -> d.caseId()).containsExactly(caseB.id());
        assertThat(asOperator(operatorX, () -> disputes.search(new DisputeQuery(null, null, null, null, null, false,
                false, null, null, null, false, false, false, false, debtorB), new PageQuery(0, 20))).content()).isEmpty();

        // pela web: CASE_VIEW
        mvc.perform(get("/api/v1/collection/debtors/{id}/interactions", debtorA)
                        .with(authentication(ActorAuthentication.of(roleActor(operatorX, DefaultRoles.OPERADOR)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/collection/debtors/{id}/interactions", debtorA)
                        .with(authentication(ActorAuthentication.of(actor(operatorX, EnumSet.of(Permission.DEBTOR_VIEW))))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/collection/disputes").param("debtorId", debtorA.toString())
                        .with(authentication(ActorAuthentication.of(roleActor(operatorX, DefaultRoles.OPERADOR)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /** CPF válido a partir dos 9 primeiros dígitos. */
    private static String cpf(int base) {
        String digits = String.format("%09d", base);
        for (int round = 0; round < 2; round++) {
            int sum = 0;
            for (int i = 0; i < digits.length(); i++) {
                sum += (digits.charAt(i) - '0') * (digits.length() + 1 - i);
            }
            int check = 11 - sum % 11;
            digits += check >= 10 ? 0 : check;
        }
        return digits;
    }

    @Test
    void casesOnlyWithCaseViewAndSearchRequiresDebtorView() {
        Set<Permission> debtorOnly = EnumSet.of(Permission.DEBTOR_VIEW);
        QuickSearchView noCases = tenantExecution.callAs(actor(operatorX, debtorOnly), () -> quickSearch.search(CPF_A));
        assertThat(noCases.debtors()).hasSize(1);
        assertThat(noCases.cases()).as("sem CASE_VIEW, sem casos").isEmpty();

        // estratégia tem CASE_VIEW mas não DEBTOR_VIEW: busca por pessoa recusada (e registrada como acesso negado)
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.ESTRATEGIA, CreditorScope.ALL,
                () -> quickSearch.search("Devedor"))).isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void webEndpointTakesTheTermInTheBodyOnly() throws Exception {
        ActorAuthentication operator = ActorAuthentication.of(roleActor(operatorX, DefaultRoles.OPERADOR));
        mvc.perform(post("/api/v1/collection/quick-search").with(authentication(operator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"term\":\"" + CPF_A + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.debtors[0].id").value(debtorA.toString()))
                .andExpect(jsonPath("$.debtors[0].maskedDocument").value("***.018.159-**"))
                .andExpect(jsonPath("$.debtors[0].birthDate").doesNotExist())
                .andExpect(jsonPath("$.cases[0].id").value(caseA.id().toString()));
        // sem busca por GET (o termo iria para a URL)
        mvc.perform(get("/api/v1/collection/quick-search").param("term", "Devedor").with(authentication(operator)))
                .andExpect(status().is4xxClientError());
        // sem DEBTOR_VIEW: 403
        mvc.perform(post("/api/v1/collection/quick-search")
                        .with(authentication(ActorAuthentication.of(roleActor(Ids.newId(), DefaultRoles.ESTRATEGIA))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"term\":\"Devedor\"}"))
                .andExpect(status().isForbidden());
        // termo acima do limite: 400
        mvc.perform(post("/api/v1/collection/quick-search").with(authentication(operator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"term\":\"" + "x".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private Actor actor(UUID userId, Set<Permission> permissions) {
        return new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "TESTE", permissions, CreditorScope.ALL);
    }

    private Actor roleActor(UUID userId, String role) {
        return actor(userId, DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions());
    }
}
