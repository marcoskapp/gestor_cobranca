package br.com.gestorcobranca.negotiation;

import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityProvisioning;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.AssignCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.EffectiveAuthorityView;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.ProfileView;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.PolicyView;
import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases.IndexCommand;
import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases.RateInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.JustificationInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ValidateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ValidationView;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyOverride;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos do módulo negotiation (parte 1) contra PostgreSQL real: semeadura de alçadas por evento,
 * ciclo de vida e imutabilidade de políticas, índice manual, simulação com escada e alçada,
 * assistente com o mesmo guard, validação de condições, escopo por credor e RLS.
 */
class NegotiationModuleIT extends NegotiationTestSupport {

    @Autowired
    AuthorityUseCases authority;
    @Autowired
    AuthorityProvisioning authorityProvisioning;
    @Autowired
    MonetaryIndexUseCases indexes;
    @Autowired
    NegotiationSimulationUseCases simulations;
    @Autowired
    DebtLedgerPort ledger;
    @Autowired
    JsonMapper jsonMapper;

    private SimulateCommand simulate(Integer level, JustificationInput justification, List<PlanInput> plans) {
        return new SimulateCommand(creditorA, debtor, List.of(), null, level, justification, plans, "PHONE");
    }

    @Test
    void provisioningSeedsAuthorityProfilesIdempotentlyAndGrantsPermission() {
        provisionTenant("seed");
        assertThat(system(() -> authorityProvisioning.ensureDefaultProfiles())).isZero();
        List<ProfileView> profiles = asManager(() -> authority.listProfiles());
        assertThat(profiles).extracting(ProfileView::code).containsExactly("ASSISTENTE_INICIAL", "GESTOR", "JUNIOR",
                "PLENO", "SENIOR", "SUPERVISOR");
        assertThat(count("""
                select count(*) from identity.role_permission rp join identity.role r on r.id = rp.role_id
                where r.code in ('ADMIN', 'GESTOR') and rp.permission = 'AUTHORITY_MANAGE'""")).isEqualTo(2);
        // operador não administra alçadas
        assertThatThrownBy(() -> asOperator(() -> authority.listProfiles())).isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void policyTermsRoundTripThroughTheApiMapperWithoutDerivedProperties() {
        PolicyTerms template = StandardPolicyTerms.marketDefault();
        String json = jsonMapper.writeValueAsString(template);
        assertThat(json).doesNotContain("topLevel", "allZero", "referencedPortfolios");
        assertThat(jsonMapper.readValue(json, PolicyTerms.class)).isEqualTo(template);
    }

    @Test
    void policyLifecycleVersioningAndDatabaseImmutability() {
        provisionTenant("policy");
        UUID v1 = publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        PolicyView draft = asManager(() -> policies.newVersion(v1));
        assertThat(draft.version()).isEqualTo(2);
        assertThatThrownBy(() -> asManager(() -> policies.newVersion(v1))).hasMessageContaining("rascunho");
        PolicyView v2 = asManager(() -> policies.publish(draft.id(), today.plusDays(7)));
        assertThat(asManager(() -> policies.get(v1)).effectiveTo()).isEqualTo(today.plusDays(6));
        assertThat(asOperator(() -> policies.effective(creditorA, null, null, today)).version()).isEqualTo(1);
        assertThat(asOperator(() -> policies.effective(creditorA, null, null, today.plusDays(7))).version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("PUBLISHED");
        // credor sem política
        assertThatThrownBy(() -> asOperator(() -> policies.effective(creditorB, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        // versão publicada é imutável no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update negotiation.collection_policy set terms = '{}'::jsonb where id = ?", v1)))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("imutável");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update negotiation.collection_policy set effective_to = null where id = ?", v1)))
                .isInstanceOf(DataAccessException.class);
        // operador consulta, mas não altera; operador restrito ao credor B não vê a política de A
        assertThatThrownBy(() -> asOperator(() -> policies.newVersion(v1))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(operator, DefaultRoles.OPERADOR, CreditorScope.of(Set.of(creditorB)),
                () -> policies.get(v1))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.audit_event where entity_type = 'collection_policy'"))
                .isGreaterThanOrEqualTo(4);
        assertThat(count("select count(*) from shared.business_event where event_type = 'policy.published' "
                + "and attributes ->> 'policyKind' = 'collection'")).isEqualTo(2);
    }

    @Test
    void overridePortfolioMustBelongToCreditorAndIndexMustExist() {
        provisionTenant("override");
        PolicyRules base = StandardPolicyTerms.marketDefault().base();
        PolicyTerms foreignPortfolio = new PolicyTerms(base, List.of(new PolicyOverride("CARTEIRA_B", portfolioB, null,
                null, null, null, null, new PolicyRules.Validity(3, 5), null, null)));
        assertThatThrownBy(() -> publishPolicy(creditorA, foreignPortfolio)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não pertence");
        PolicyRules.Charges withIpca = new PolicyRules.Charges(new BigDecimal("1"), PolicyRules.InterestMode.SIMPLE,
                new BigDecimal("2"), "IPCA", List.of(), new BigDecimal("10"), PolicyRules.HonorariumBase.UPDATED_DEBT);
        PolicyTerms ownPortfolioWithIndex = new PolicyTerms(base, List.of(new PolicyOverride("CARTEIRA_A", portfolioA,
                null, null, withIpca, null, null, null, null, null)));
        assertThatThrownBy(() -> publishPolicy(creditorA, ownPortfolioWithIndex)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Índice");
        asManager(() -> indexes.create(new IndexCommand("IPCA", "IPCA (IBGE)", "manual")));
        publishPolicy(creditorA, ownPortfolioWithIndex);
        assertThat(asOperator(() -> policies.effective(creditorA, portfolioA, 10L, null)).overrideCode())
                .isEqualTo("CARTEIRA_A");
        assertThat(asOperator(() -> policies.effective(creditorA, null, 10L, null)).overrideCode()).isNull();
    }

    @Test
    void simulationOffersFollowLadderAuthorityAndAreRecordedWithoutPersonalData() {
        provisionTenant("simulate");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());

        // nível 0: sem desconto; títulos de 1000 (60 dias) e 500 (30 dias)
        // juros 20,00 + 5,00; multa 20,00 + 10,00; honorários 10% × (1040 + 515) = 155,50 → total 1.710,50
        SimulationView level0 = asOperator(() -> simulations.simulate(simulate(null, null, List.of())));
        var result = level0.result();
        assertThat(result.composition().totalAmount()).isEqualByComparingTo("1710.50");
        assertThat(result.composition().total().interest()).isEqualByComparingTo("25.00");
        assertThat(result.composition().maxDaysOverdue()).isEqualTo(60);
        assertThat(result.actor().profileCode()).isEqualTo("JUNIOR");
        assertThat(result.grantedLevel()).isZero();
        assertThat(result.nextLevelAvailable()).isTrue();
        assertThat(result.envelope()).as("júnior não vê o envelope completo").isNull();
        // à vista e o maior parcelamento do júnior (6)
        assertThat(result.offers()).extracting(OfferView::installments).containsExactly(1, 6);
        assertThat(result.offers()).allMatch(o -> o.valid() && o.withinAuthority());
        assertThat(result.offers().getFirst().total()).isEqualByComparingTo("1710.50");

        // subir de nível exige justificativa
        assertThatThrownBy(() -> asOperator(() -> simulations.simulate(simulate(1, null, List.of()))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("justificativa");

        // pedido de nível 2 para o júnior: ofertas até o nível 1 (limitado), com justificativa registrada
        SimulationView escalated = asOperator(() -> simulations.simulate(simulate(2,
                new JustificationInput("PAYMENT_CAPACITY", "Renda reduzida"), List.of(new PlanInput(1, null, null)))));
        assertThat(escalated.result().grantedLevel()).isEqualTo(1);
        assertThat(escalated.result().levelCapped()).isTrue();
        OfferView cashLevel1 = escalated.result().offers().stream().filter(o -> o.level() == 1).findFirst().orElseThrow();
        // nível 1 à vista: juros e multa até mínimo(100% × 50% do nível, 100% × 50% do júnior) = 50%
        // desconto de juros 20,00 × 50% + 5,00 × 50% = 12,50; honorários não descontados no nível 1
        assertThat(cashLevel1.discountRates().interest()).isEqualByComparingTo("50");
        assertThat(cashLevel1.discountRates().honorarium()).isEqualByComparingTo("0");
        assertThat(cashLevel1.discount().interest()).isEqualByComparingTo("12.50");
        assertThat(cashLevel1.withinAuthority()).isTrue();

        // com o perfil GESTOR (envelope inteiro), o envelope completo é exibido
        assignProfile(manager, "GESTOR");
        SimulationView managerView = asManager(() -> simulations.simulate(simulate(null, null, List.of())));
        assertThat(managerView.result().envelope()).hasSize(3);
        assertThat(managerView.result().actor().profileCode()).isEqualTo("GESTOR");

        // registro persistido e reproduzível
        SimulationView reloaded = asOperator(() -> simulations.get(escalated.id()));
        assertThat(reloaded.result()).isEqualTo(escalated.result());
        assertThat(count("select count(*) from negotiation.simulation where justification_reason = 'PAYMENT_CAPACITY'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update negotiation.simulation set granted_level = 0 where id = ?", escalated.id())))
                .isInstanceOf(DataAccessException.class);

        // evento de negócio sem dados pessoais
        assertThat(count("select count(*) from shared.business_event where event_type = 'negotiation.simulated'"))
                .isEqualTo(3);
        String attributes = system(() -> jdbcTemplate.queryForObject("""
                select string_agg(attributes::text, ' ') from shared.business_event
                where event_type = 'negotiation.simulated'""", String.class));
        assertThat(attributes).doesNotContain("Maria", "529", "Renda").contains("PAYMENT_CAPACITY");
        assertThat(count("select count(*) from shared.audit_event where action = 'negotiation.concession_escalated'"))
                .isEqualTo(1);
    }

    @Test
    void disputedTitleIsExcludedFromSimulationAndRejectedInValidation() {
        provisionTenant("dispute");
        UUID policyId = publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        system(() -> {
            ledger.markDisputed(List.of(title30), Ids.newId(), true);
            return null;
        });
        SimulationView view = asOperator(() -> simulations.simulate(simulate(null, null, List.of())));
        assertThat(view.result().composition().titles()).singleElement()
                .satisfies(t -> assertThat(t.titleId()).isEqualTo(title60));
        // na validação (como na proposta), pedir o título contestado reprova as condições
        ValidationView validation = asOperator(() -> simulations.validate(new ValidateCommand(creditorA, debtor,
                List.of(title60, title30), null, policyId, 0, null, null, new PlanInput(1, null, null), null)));
        assertThat(validation.outcome()).isEqualTo("REJECTED");
        assertThat(validation.violations()).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("title.dispute_blocked");
            assertThat(v.titleId()).isEqualTo(title30);
        });
    }

    @Test
    void validationSeparatesPolicyFromAuthorityAndChecksPolicyVersion() {
        provisionTenant("validate");
        UUID policyId = publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        assignProfile(manager, "GESTOR");
        JustificationInput why = new JustificationInput("IMMEDIATE_PAYMENT", null);
        // honorários 40% à vista no nível 2: política permite 50%; júnior (nível 1, 50% do envelope) não
        ValidationView needsApproval = asOperator(() -> simulations.validate(new ValidateCommand(creditorA, debtor,
                List.of(), null, policyId, 2, why, new ComponentsView(null, null, null, null, null, new BigDecimal("40")),
                new PlanInput(1, null, null), "PHONE")));
        assertThat(needsApproval.outcome()).isEqualTo("REQUIRES_APPROVAL");
        assertThat(needsApproval.violations()).extracting(v -> v.code())
                .containsExactlyInAnyOrder("authority.level_exceeded", "authority.discount_exceeded");
        // o mesmo pedido pelo gestor está dentro da alçada
        assertThat(asManager(() -> simulations.validate(new ValidateCommand(creditorA, debtor, List.of(), null, policyId,
                2, why, new ComponentsView(null, null, null, null, null, new BigDecimal("40")),
                new PlanInput(1, null, null), null))).outcome()).isEqualTo("WITHIN_AUTHORITY");
        // 60% de honorários excede a política: ninguém aprova
        ValidationView rejected = asManager(() -> simulations.validate(new ValidateCommand(creditorA, debtor, List.of(),
                null, policyId, 2, why, new ComponentsView(null, null, null, null, null, new BigDecimal("60")),
                new PlanInput(1, null, null), null)));
        assertThat(rejected.outcome()).isEqualTo("REJECTED");
        // versão esperada diferente da vigente
        assertThat(asManager(() -> simulations.validate(new ValidateCommand(creditorA, debtor, List.of(), null,
                Ids.newId(), 0, null, null, new PlanInput(1, null, null), null))).violations())
                .extracting(v -> v.code()).containsExactly("policy.version_not_effective");
    }

    @Test
    void assistantUsesItsOwnAuthorityProfileThroughTheSameGuard() {
        provisionTenant("assistant");
        UUID policyId = publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        UUID assistantPolicy = Ids.newId();
        SimulationView view = asAssistant(assistantPolicy, creditorA, () -> simulations.simulate(simulate(1,
                new JustificationInput("PAYMENT_CAPACITY", null), List.of())));
        assertThat(view.result().actor().type()).isEqualTo("ASSISTANT");
        assertThat(view.result().actor().profileCode()).isEqualTo("ASSISTENTE_INICIAL");
        assertThat(view.result().envelope()).as("o assistente nunca recebe o envelope").isNull();
        assertThat(view.result().limits().canFormalize()).isFalse();
        // ASSISTENTE_INICIAL: até 6 pagamentos e R$ 3.000
        assertThat(view.result().offers()).extracting(OfferView::installments).contains(6);
        // acima da alçada do assistente: requer aprovação (na parte 2: transferência para humano)
        ValidationView v = asAssistant(assistantPolicy, creditorA, () -> simulations.validate(new ValidateCommand(creditorA,
                debtor, List.of(), null, policyId, 2, new JustificationInput("PAYMENT_CAPACITY", null),
                new ComponentsView(null, null, null, null, null, new BigDecimal("40")), new PlanInput(1, null, null),
                "WHATSAPP")));
        assertThat(v.outcome()).isEqualTo("REQUIRES_APPROVAL");
        // o assistente só atua no credor da conversa
        assertThatThrownBy(() -> asAssistant(assistantPolicy, creditorB, () -> simulations.simulate(
                simulate(null, null, List.of())))).isInstanceOf(ScopeAccessDeniedException.class);
        // e não relê simulações de humanos
        SimulationView human = asOperator(() -> simulations.simulate(simulate(null, null, List.of())));
        assertThatThrownBy(() -> asAssistant(assistantPolicy, creditorA, () -> simulations.get(human.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.business_event where event_type = 'negotiation.simulated' "
                + "and actor_type = 'ASSISTANT'")).isEqualTo(1);
    }

    @Test
    void assignmentByCreditorPrevailsAndIsAuditedWithoutSelfAssignment() {
        provisionTenant("assign");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        UUID senior = asManager(() -> authority.listProfiles()).stream().filter(p -> p.code().equals("SENIOR"))
                .findFirst().orElseThrow().id();
        UUID pleno = asManager(() -> authority.listProfiles()).stream().filter(p -> p.code().equals("PLENO"))
                .findFirst().orElseThrow().id();
        asManager(() -> authority.assign(new AssignCommand("USER", operator, null, pleno)));
        asManager(() -> authority.assign(new AssignCommand("USER", operator, creditorA, senior)));
        EffectiveAuthorityView onA = asOperator(() -> authority.mine(creditorA));
        assertThat(onA.profile().code()).isEqualTo("SENIOR");
        assertThat(onA.source()).isEqualTo("ASSIGNED_CREDITOR");
        assertThat(asOperator(() -> authority.mine(creditorB)).profile().code()).isEqualTo("PLENO");
        assertThat(asOperator(() -> simulations.simulate(simulate(3, new JustificationInput("OTHER", "Cliente antigo"),
                List.of()))).result().grantedLevel()).isEqualTo(3);
        // troca do perfil reaproveita a atribuição
        asManager(() -> authority.assign(new AssignCommand("USER", operator, creditorA, pleno)));
        assertThat(asManager(() -> authority.assignments("USER", operator))).hasSize(2);
        // ninguém atribui a própria alçada
        assertThatThrownBy(() -> asManager(() -> authority.assign(new AssignCommand("USER", manager, null, senior))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("própria");
        // atribuição geral exige escopo de todos os credores
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, CreditorScope.of(Set.of(creditorA)),
                () -> authority.assign(new AssignCommand("USER", operator, null, senior))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'PERMISSION_CHANGED' "
                + "and details ->> 'change' = 'negotiation_authority'")).isEqualTo(3);
        assertThat(count("select count(*) from shared.audit_event where entity_type = 'authority_assignment'"))
                .isEqualTo(3);
    }

    @Test
    void manualIndexCorrectsDebtAndTenantsAreIsolated() {
        provisionTenant("index-a");
        asManager(() -> indexes.create(new IndexCommand("IPCA", "IPCA (IBGE)", "manual")));
        YearMonth current = YearMonth.from(today);
        asManager(() -> indexes.putRates("IPCA", List.of(new RateInput(current.minusMonths(3), new BigDecimal("0.40")),
                new RateInput(current.minusMonths(2), new BigDecimal("0.50")),
                new RateInput(current.minusMonths(1), new BigDecimal("0.30")))));
        PolicyTerms base = StandardPolicyTerms.marketDefault();
        PolicyRules.Charges charges = new PolicyRules.Charges(new BigDecimal("1"), PolicyRules.InterestMode.SIMPLE,
                new BigDecimal("2"), "IPCA", List.of(), new BigDecimal("10"), PolicyRules.HonorariumBase.UPDATED_DEBT);
        PolicyRules rules = new PolicyRules(charges, base.base().discountTiers(), base.base().installments(),
                base.base().validity(), base.base().ladder(), base.base().breakRule());
        publishPolicy(creditorA, new PolicyTerms(rules, List.of()));
        SimulationView view = asOperator(() -> simulations.simulate(simulate(null, null, List.of())));
        assertThat(view.result().composition().total().correction()).isPositive();
        assertThat(view.result().composition().titles().getFirst().lines())
                .anySatisfy(l -> assertThat(l.rule()).isEqualTo("INDEX_CORRECTION"));
        // mês corrente sem taxa: aviso na memória
        assertThat(view.result().notes()).anySatisfy(n -> assertThat(n).contains(current.toString()));
        // correção auditada
        asManager(() -> indexes.putRates("IPCA", List.of(new RateInput(current.minusMonths(1), new BigDecimal("0.35")))));
        assertThat(count("select count(*) from shared.audit_event where action = 'monetary_index.rates_changed'"))
                .isEqualTo(2);
        UUID simulationA = view.id();
        UUID tenantA = tenant;

        provisionTenant("index-b");
        assertThat(count("select count(*) from negotiation.monetary_index")).isZero();
        assertThat(count("select count(*) from negotiation.simulation")).isZero();
        assertThatThrownBy(() -> asManager(() -> simulations.get(simulationA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("""
                insert into negotiation.monetary_index (id, tenant_id, version, created_at, updated_at, code, name)
                values (?, ?, 0, now(), now(), 'X1', 'x')""", Ids.newId(), tenantA)))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        // sem tenant no contexto nada é visível
        assertThat(jdbcTemplate.queryForObject("select count(*) from negotiation.simulation", Long.class)).isZero();
        // e o simulador sem política vigente recusa
        assertThatThrownBy(() -> asOperator(() -> simulations.simulate(simulate(null, null, List.of()))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("política");
    }
}
