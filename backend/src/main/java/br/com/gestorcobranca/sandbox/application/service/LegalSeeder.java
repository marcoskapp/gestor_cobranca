package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.AuthorizationView;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.DecisionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.FileUpload;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.UploadCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases.ExpenseCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.AssignCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterFilter;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.ReferCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.TransitionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentInput;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ChecklistEntry;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ViabilityResultView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementCommand;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import br.com.gestorcobranca.sandbox.domain.service.CnjNumbers;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fluxo jurídico de demonstração na carteira com garantia de veículo do 1º credor: um caso conduzido até a ação
 * ajuizada e citada (viabilidade, autorização do credor com evidência, processo com número CNJ válido, prazo de
 * resposta, custas) e atribuído ao escritório parceiro; outro só indicado e em análise. Conduzido pelo jurídico
 * interno, pelos casos de uso do módulo legal (mesmas validações de etapa, orçamento e autorização da operação).
 */
@SandboxSeedComponent
class LegalSeeder {

    private static final byte[] EVIDENCE = ("%PDF-1.4\n% autorizacao ficticia do credor (sandbox)\n1 0 obj<<>>endobj\n"
            + "trailer<<>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    /** Carteira de veículos do 1º credor (índice no catálogo). */
    private static final int VEHICLE_PORTFOLIO = 1;

    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final LegalMatterUseCases matters;
    private final LegalViabilityUseCases viability;
    private final CreditorAuthorizationUseCases authorizations;
    private final LegalDocumentUseCases documents;
    private final LawsuitUseCases lawsuits;
    private final LegalExpenseUseCases expenses;
    private final SeedRuntime rt;

    LegalSeeder(DebtorUseCases debtors, CaseUseCases cases, LegalMatterUseCases matters,
                LegalViabilityUseCases viability, CreditorAuthorizationUseCases authorizations,
                LegalDocumentUseCases documents, LawsuitUseCases lawsuits, LegalExpenseUseCases expenses, SeedRuntime rt) {
        this.debtors = debtors;
        this.cases = cases;
        this.matters = matters;
        this.viability = viability;
        this.authorizations = authorizations;
        this.documents = documents;
        this.lawsuits = lawsuits;
        this.expenses = expenses;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        rt.optional("caso jurídico ajuizado (processo em andamento)", () -> fileLawsuit(seed, 0));
        rt.optional("caso jurídico em análise", () -> {
            Optional<CaseView> c = activeCase(seed, 1);
            if (c.isPresent() && openMatter(seed, c.get()).isEmpty()) {
                MatterView m = refer(seed, c.get(), "Garantia localizada; credor pediu avaliação jurídica");
                rt.as(seed.actor("juridico"), () -> matters.transition(m.id(), new TransitionCommand("UNDER_REVIEW",
                        "Indicação aceita para análise de viabilidade", null, null)));
            }
        });
    }

    private void fileLawsuit(TenantSeed seed, int debtorIndex) {
        Optional<CaseView> collectionCase = activeCase(seed, debtorIndex);
        if (collectionCase.isEmpty() || openMatter(seed, collectionCase.get()).isPresent()) {
            return;
        }
        Actor juridico = seed.actor("juridico");
        MatterView matter = refer(seed, collectionCase.get(), "Atraso relevante, título executivo e veículo em garantia");
        UUID id = matter.id();
        rt.as(juridico, () -> matters.transition(id, new TransitionCommand("UNDER_REVIEW", "Indicação aceita", null, null)));

        // viabilidade: rotas e checklist sugeridos pelo próprio módulo, com documentos conferidos
        AssessmentInput input = new AssessmentInput(null, null, new BigDecimal("75"), new BigDecimal("2500.00"));
        ViabilityResultView preview = rt.as(juridico, () -> viability.preview(id, input));
        List<String> routes = preview.suggestedRoutes().isEmpty() ? List.copyOf(preview.availableRoutes())
                : preview.suggestedRoutes();
        Set<String> chosen = new LinkedHashSet<>(routes.subList(0, Math.min(2, routes.size())));
        if (preview.availableRoutes().contains("EXECUTION")) {
            chosen.add("EXECUTION");
        }
        List<ChecklistEntry> checklist = preview.suggestedChecklist().stream()
                .map(c -> new ChecklistEntry(c.code(), c.label(), "PRESENT", c.required())).toList();
        rt.as(juridico, () -> viability.record(id, new AssessmentCommand(input, true, List.copyOf(chosen), checklist,
                "Veículo localizado em consulta ao DETRAN (fictício)", "Execução do título com busca do bem (fictício)")));

        // autorização do credor: solicitada na transição, concedida com evidência anexada
        rt.as(juridico, () -> matters.transition(id, new TransitionCommand("AWAITING_AUTHORIZATION", "Viável", null, null)));
        AuthorizationView pending = rt.as(juridico, () -> authorizations.list(id)).getFirst();
        UUID evidence = rt.as(juridico, () -> documents.upload(id, new UploadCommand("AUTHORIZATION_EVIDENCE",
                "AUTHORIZATION", pending.id(), "E-mail do credor autorizando o ajuizamento (fictício)"),
                new FileUpload("autorizacao-credor.pdf", "application/pdf", EVIDENCE.length,
                        new ByteArrayInputStream(EVIDENCE)))).id();
        rt.as(juridico, () -> authorizations.decide(pending.id(), new DecisionCommand("GRANTED",
                Set.copyOf(pending.requestedRoutes()), new BigDecimal("2500.00"), seed.today, "EMAIL",
                "Gerente de recuperação do credor (fictício)", evidence, "Aprovado conforme política do credor")));

        // ajuizamento (número CNJ fictício e válido) e citação com prazo para embargos
        String route = pending.requestedRoutes().contains("EXECUTION") ? "EXECUTION"
                : pending.requestedRoutes().stream().filter(r -> !Set.of("PROTEST", "CREDIT_BUREAU_LISTING",
                "EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT").contains(r)).findFirst().orElse("EXECUTION");
        long sequential = Math.floorMod(seed.spec.seed() + debtorIndex * 7919L, 9_000_000L) + 1_000_000L;
        String cnj = CnjNumbers.format(sequential, seed.today.getYear(), 8, 26, 100);
        LawsuitView lawsuit = rt.as(juridico, () -> lawsuits.register(id, new LawsuitCommand(cnj, route,
                "Execução de título extrajudicial", null, Set.of(), matter.debtAmount(), seed.today.minusDays(20), "TJSP",
                "1ª Vara Cível do Foro Central (fictícia)")));
        rt.as(juridico, () -> lawsuits.recordMovement(lawsuit.id(), new MovementCommand(seed.today.minusDays(3),
                "CITATION", "Citação positiva do executado (fictício)", null, null, null, new DeadlineCommand("RESPONSE",
                "Prazo para embargos à execução", null, null, seed.today.minusDays(3), null, 15, null, null,
                seed.user("juridico")))));
        rt.as(juridico, () -> expenses.record(id, new ExpenseCommand("COURT_FEES", "Custas iniciais (fictício)",
                new BigDecimal("780.00"), seed.today.minusDays(20), "CREDITOR", true, lawsuit.id(), null)));
        // acompanhamento pelo escritório parceiro (o advogado parceiro só vê os casos atribuídos ao escritório)
        rt.as(juridico, () -> matters.assign(id, new AssignCommand("LAW_FIRM", null, seed.lawFirmId)));
    }

    private MatterView refer(TenantSeed seed, CaseView c, String reason) {
        return rt.as(seed.actor("juridico"), () -> matters.refer(new ReferCommand(c.id(), Set.of(),
                reason + " (cenário fictício)")));
    }

    private Optional<MatterView> openMatter(TenantSeed seed, CaseView c) {
        return rt.as(seed.actor("juridico"), () -> matters.search(new MatterFilter(null, null, null, c.id(), null, null,
                null, null, true), PageQuery.of(0, 1))).content().stream().findFirst();
    }

    private Optional<CaseView> activeCase(TenantSeed seed, int debtorIndex) {
        Debt debt = seed.debt(0, VEHICLE_PORTFOLIO, debtorIndex);
        UUID portfolioId = seed.portfolioId(0, VEHICLE_PORTFOLIO);
        return rt.system(seed.tenantId, () -> debtors.findByDocument(debt.debtor().document())
                .flatMap(d -> cases.findActive(d.id(), portfolioId)));
    }
}
