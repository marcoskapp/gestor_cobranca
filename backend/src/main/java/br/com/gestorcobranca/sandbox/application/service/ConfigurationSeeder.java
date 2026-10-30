package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases.RegisterLegalBasisCommand;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalUnitOptions;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.RuleCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.AssignCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.ProfileView;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.PolicyView;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases;
import br.com.gestorcobranca.sandbox.application.service.catalog.IntegrationPricingCatalog;
import br.com.gestorcobranca.sandbox.application.service.catalog.NegotiationCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetCommand;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Configuração operacional de cada assessoria, feita por quem tem o perfil adequado: política de contato e bases
 * legais (compliance), políticas de negociação e alçadas (gestor/administrador), comissionamento da assessoria
 * (financeiro edita, administrador publica — segregação), equipe e filas (supervisor), metas dos negociadores (gestor)
 * e jurídico (configuração, escritório parceiro e regra de indicação).
 */
@SandboxSeedComponent
class ConfigurationSeeder {

    private static final List<String> WEEKDAYS_AND_SATURDAY = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
            "FRIDAY", "SATURDAY");
    private static final List<String> WEEKDAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");

    private final ContactPolicyUseCases contactPolicies;
    private final LegalBasisUseCases legalBases;
    private final CollectionPolicyUseCases policies;
    private final AuthorityUseCases authority;
    private final CommissionRuleUseCases commissionRules;
    private final ContractIntegrationPricingUseCases integrationPricing;
    private final TeamUseCases teams;
    private final QueueUseCases queues;
    private final CommissionPlanUseCases plans;
    private final LegalSettingsUseCases legalSettings;
    private final LawFirmUseCases lawFirms;
    private final LegalEligibilityUseCases legalEligibility;
    private final SeedRuntime rt;

    ConfigurationSeeder(ContactPolicyUseCases contactPolicies, LegalBasisUseCases legalBases,
                        CollectionPolicyUseCases policies, AuthorityUseCases authority,
                        CommissionRuleUseCases commissionRules,
                        ContractIntegrationPricingUseCases integrationPricing, TeamUseCases teams, QueueUseCases queues,
                        CommissionPlanUseCases plans, LegalSettingsUseCases legalSettings, LawFirmUseCases lawFirms,
                        LegalEligibilityUseCases legalEligibility, SeedRuntime rt) {
        this.contactPolicies = contactPolicies;
        this.legalBases = legalBases;
        this.policies = policies;
        this.authority = authority;
        this.commissionRules = commissionRules;
        this.integrationPricing = integrationPricing;
        this.teams = teams;
        this.queues = queues;
        this.plans = plans;
        this.legalSettings = legalSettings;
        this.lawFirms = lawFirms;
        this.legalEligibility = legalEligibility;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        compliance(seed);
        negotiation(seed);
        commission(seed);
        integrationPricing(seed);
        teamAndQueues(seed);
        legal(seed);
    }

    /** Metas do mês corrente para os negociadores (depois dos usuários existirem). */
    void goals(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        YearMonth period = YearMonth.from(seed.today);
        Set<UUID> withGoal = rt.as(gestor, () -> plans.goals(period)).stream()
                .map(CommissionPlanUseCases.GoalView::subjectId).collect(Collectors.toSet());
        for (String key : List.of("operador", "operador2")) {
            UUID userId = seed.user(key);
            if (withGoal.contains(userId)) {
                continue;
            }
            GoalUnitOptions options = rt.as(gestor, () -> plans.goalUnitOptions(period, "USER", userId));
            String unit = options.units().isEmpty() ? "CURRENCY" : options.units().getFirst();
            BigDecimal target = "CURRENCY".equals(unit) ? new BigDecimal(key.equals("operador") ? "45000.00" : "25000.00")
                    : new BigDecimal(key.equals("operador") ? "60" : "40");
            rt.optional("meta do negociador " + key, () -> rt.as(gestor, () -> plans.setGoal(
                    new GoalCommand(period, "USER", userId, target, unit))));
        }
    }

    // ------------------------------------------------------------------------------------------------ compliance

    private void compliance(TenantSeed seed) {
        Actor compliance = seed.actor("compliance");
        if (rt.as(compliance, () -> contactPolicies.history(null)).isEmpty()) {
            rt.as(compliance, () -> contactPolicies.publish(new PublishPolicyCommand(null, null,
                    "Política de contato padrão da assessoria (sandbox)", List.of(
                    new ChannelRuleInput("WHATSAPP", LocalTime.of(8, 0), LocalTime.of(20, 0), WEEKDAYS_AND_SATURDAY, 3, 10,
                            120, true),
                    new ChannelRuleInput("SMS", LocalTime.of(9, 0), LocalTime.of(19, 0), WEEKDAYS, 2, 6, 240, true),
                    new ChannelRuleInput("EMAIL", LocalTime.of(8, 0), LocalTime.of(20, 0), WEEKDAYS_AND_SATURDAY, 2, 7, 60,
                            false),
                    new ChannelRuleInput("PHONE", LocalTime.of(8, 0), LocalTime.of(20, 0), WEEKDAYS_AND_SATURDAY, 3, 12, 60,
                            true)))));
        }
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec spec = seed.creditor(c);
            UUID creditorId = seed.creditorId(c);
            if (rt.as(compliance, () -> legalBases.listByCreditor(creditorId)).isEmpty()) {
                rt.as(compliance, () -> legalBases.register(new RegisterLegalBasisCommand(creditorId, null,
                        "EXERCICIO_REGULAR_DIREITOS", "OPERADOR", "CPS-" + spec.prefix(),
                        seed.contractStarts.get(spec.prefix()), null, "Encarregado de dados (fictício)", 60,
                        "Cobrança em nome do credor, conforme contrato de prestação de serviços (sandbox)")));
            }
        }
        // o primeiro credor restringe o horário do telefone (regra própria sobre a da assessoria)
        UUID first = seed.creditorId(0);
        if (rt.as(compliance, () -> contactPolicies.history(first)).isEmpty()) {
            rt.as(compliance, () -> contactPolicies.publish(new PublishPolicyCommand(first, null,
                    "Credor pede ligações somente em dias úteis (sandbox)", List.of(
                    new ChannelRuleInput("PHONE", LocalTime.of(9, 0), LocalTime.of(19, 0), WEEKDAYS, 2, 8, 120, true)))));
        }
    }

    // ------------------------------------------------------------------------------------------------ negociação

    private void negotiation(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec spec = seed.creditor(c);
            UUID creditorId = seed.creditorId(c);
            List<PolicyView> existing = rt.as(gestor, () -> policies.search(creditorId, null, PageQuery.of(0, 20)))
                    .content();
            if (existing.stream().anyMatch(p -> "PUBLISHED".equals(p.status()))) {
                continue;
            }
            UUID draft = existing.stream().filter(p -> "DRAFT".equals(p.status())).map(PolicyView::id).findFirst()
                    .orElseGet(() -> rt.as(gestor, () -> policies.createDraft(new CreatePolicyCommand(creditorId,
                            "Política de negociação — " + spec.tradeName(),
                            "Alçadas, descontos máximos, entrada mínima e parcelamento do credor (sandbox)",
                            NegotiationCatalog.policy(spec.segment())))).id());
            rt.as(gestor, () -> policies.publish(draft, seed.today));
        }
        // alçadas: operador principal pleno, supervisor e gestor com os perfis de mesmo nome; o segundo operador fica
        // no padrão (júnior) — as propostas acima da alçada dele vão para a fila de aprovação
        Actor admin = seed.actor("admin");
        Map<String, UUID> profiles = rt.as(admin, authority::listProfiles).stream()
                .collect(Collectors.toMap(ProfileView::code, ProfileView::id));
        assignAuthority(seed, admin, "operador", profiles.get("PLENO"));
        assignAuthority(seed, admin, "supervisor", profiles.get("SUPERVISOR"));
        assignAuthority(seed, admin, "gestor", profiles.get("GESTOR"));
    }

    private void assignAuthority(TenantSeed seed, Actor admin, String key, UUID profileId) {
        UUID userId = seed.user(key);
        if (rt.as(admin, () -> authority.assignments("USER", userId)).isEmpty()) {
            rt.as(admin, () -> authority.assign(new AssignCommand("USER", userId, null, profileId)));
        }
    }

    // ------------------------------------------------------------------------------------------------ comissão

    private void commission(TenantSeed seed) {
        Actor financeiro = seed.actor("financeiro");
        Actor admin = seed.actor("admin");
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec spec = seed.creditor(c);
            UUID creditorId = seed.creditorId(c);
            UUID contractId = seed.contracts.get(spec.prefix());
            List<RuleSetView> existing = rt.as(financeiro, () -> commissionRules.list(creditorId, contractId));
            if (existing.stream().anyMatch(r -> "PUBLISHED".equals(r.status()))) {
                continue;
            }
            UUID draft = existing.stream().filter(r -> "DRAFT".equals(r.status())).map(RuleSetView::id).findFirst()
                    .orElseGet(() -> rt.as(financeiro, () -> commissionRules.createDraft(new RuleSetCommand(contractId,
                            NegotiationCatalog.commission(spec.segment()),
                            "Comissionamento do contrato de prestação de serviços (sandbox)"))).id());
            // quem edita as regras não publica: o administrador publica (segregação de funções)
            rt.as(admin, () -> commissionRules.publish(draft, seed.contractStarts.get(spec.prefix())));
        }
    }

    // ------------------------------------------------------------------------------------------------ preço da API pública

    /** Condições de cobrança das integrações no contrato de cada credor (organization), definidas pelo gestor. */
    private void integrationPricing(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec spec = seed.creditor(c);
            UUID contractId = seed.contracts.get(spec.prefix());
            if (rt.as(gestor, () -> integrationPricing.history(contractId)).isEmpty()) {
                rt.as(gestor, () -> integrationPricing.define(contractId,
                        IntegrationPricingCatalog.pricing(spec.prefix(), spec.segment())));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ equipe e filas

    private void teamAndQueues(TenantSeed seed) {
        Actor supervisor = seed.actor("supervisor");
        String teamName = "Mesa de negociação";
        seed.teamId = rt.as(supervisor, teams::list).stream().filter(t -> teamName.equals(t.name())).findFirst()
                .map(TeamUseCases.TeamView::id)
                .orElseGet(() -> rt.as(supervisor, () -> teams.create(new TeamCommand(teamName, null, seed.user("supervisor"),
                        Set.of(seed.user("operador"), seed.user("operador2"))))).id());
        Map<String, QueueView> existing = rt.as(supervisor, queues::list).stream()
                .collect(Collectors.toMap(QueueView::code, q -> q));
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec spec = seed.creditor(c);
            String code = "FILA_" + spec.prefix();
            UUID creditorId = seed.creditorId(c);
            // primeiro credor distribuído em rodízio; os demais sob demanda (o operador puxa o próximo caso)
            String strategy = c == 0 ? "ROUND_ROBIN" : "PULL";
            int priority = 10 + c;
            UUID id = Optional.ofNullable(existing.get(code)).map(QueueView::id).orElseGet(() -> rt.as(supervisor,
                    () -> queues.create(new QueueCommand(code, "Fila " + spec.tradeName(),
                            "Casos do credor " + spec.tradeName() + " (sandbox)", priority, creditorId, null, null, null,
                            null, null, null, null, "OLDEST_OVERDUE", strategy, 40, Set.of(seed.teamId)))).id());
            seed.queues.put(spec.prefix(), id);
        }
    }

    // ------------------------------------------------------------------------------------------------ jurídico

    private void legal(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        Actor juridico = seed.actor("juridico");
        // escritórios parceiros liberados; credor ou escritório adiantam custas; demais padrões conservadores
        rt.as(gestor, () -> legalSettings.update(new SettingsCommand("BOTH", Set.of("CREDITOR", "LAW_FIRM"), "BLOCK",
                "EXCLUDE", "AUTHORIZATION", List.of(5, 2, 0), true, 5, 5, 10, 180, 180)));
        String firmCnpj = new FictitiousData(seed.spec.seed() * 13).cnpj();
        seed.lawFirmId = rt.as(juridico, lawFirms::list).stream().filter(f -> f.cnpj() != null && firmCnpj.equals(f.cnpj().replaceAll("\\D", ""))).findFirst()
                .map(LawFirmUseCases.LawFirmView::id)
                .orElseGet(() -> rt.as(juridico, () -> lawFirms.create(new LawFirmCommand(
                        "Rocha, Dias & Associados Advocacia (fictício)", firmCnpj, "OAB/SP 00.000 (fictício)",
                        "contato@rocha-dias.exemplo.test"))).id());
        UUID partner = seed.user("advogado");
        if (rt.as(juridico, () -> lawFirms.members(seed.lawFirmId)).stream().noneMatch(m -> m.userId().equals(partner))) {
            rt.as(juridico, () -> lawFirms.addMember(seed.lawFirmId, partner));
        }
        UUID first = seed.creditorId(0);
        if (rt.as(gestor, legalEligibility::listRules).stream().noneMatch(r -> first.equals(r.creditorId()))) {
            rt.as(gestor, () -> legalEligibility.createRule(new RuleCommand(first, null,
                    "Atraso acima de 180 dias com garantia e saldo relevante", 180, new BigDecimal("15000.00"), true, true,
                    null, null, false)));
        }
    }
}
