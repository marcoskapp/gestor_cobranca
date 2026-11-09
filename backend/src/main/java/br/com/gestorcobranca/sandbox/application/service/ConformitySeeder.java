package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintQuery;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.RegisterComplaintCommand;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases.RegisterMarkerCommand;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases.SuggestCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Conformidade da operação no sandbox (docs/tecnico/desenho/conformidade.md), só com dados fictícios: um devedor
 * superendividado com capacidade de pagamento declarada, uma sugestão do assistente a confirmar e três reclamações
 * (Procon no prazo, consumidor.gov.br perto do prazo e Reclame Aqui respondida). Idempotente: confere antes de criar.
 */
@SandboxSeedComponent
class ConformitySeeder {

    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final VulnerabilityUseCases vulnerability;
    private final ComplaintUseCases complaints;
    private final SeedRuntime rt;

    ConformitySeeder(DebtorUseCases debtors, CaseUseCases cases, VulnerabilityUseCases vulnerability,
                     ComplaintUseCases complaints, SeedRuntime rt) {
        this.debtors = debtors;
        this.cases = cases;
        this.vulnerability = vulnerability;
        this.complaints = complaints;
        this.rt = rt;
    }

    private record Target(UUID debtorId, UUID creditorId, UUID caseId) {
    }

    void seed(TenantSeed seed) {
        Actor operador = seed.actor("operador");
        Actor compliance = seed.actor("compliance");
        target(seed, 0, 0, 4).ifPresent(t -> rt.optional("marcador de superendividamento", () -> {
            if (!rt.as(compliance, () -> vulnerability.ofDebtor(t.debtorId())).vulnerable()) {
                rt.as(operador, () -> vulnerability.register(new RegisterMarkerCommand(t.debtorId(), "SUPER_INDEBTED",
                        "LEGAL_OBLIGATION", "DEBTOR_DECLARATION", new BigDecimal("180.00"))));
            }
        }));
        target(seed, 1, 0, 2).ifPresent(t -> rt.optional("sugestão de marcador do assistente", () ->
                rt.system(seed.tenantId, () -> vulnerability.suggest(new SuggestCommand(t.debtorId(), t.creditorId(),
                        "ASSISTANT", "assistant_conversation", null)))));

        String prefix = seed.creditor(0).prefix();
        target(seed, 0, 0, 6).ifPresent(t -> complaint(compliance, operador, t, "PROCON", prefix + "-PROCON-0001",
                "ABUSIVE_COLLECTION", "Consumidor relata ligações em horário de trabalho (cenário fictício)", 3, false));
        target(seed, 0, 0, 7).ifPresent(t -> complaint(compliance, operador, t, "CONSUMIDOR_GOV", prefix + "-CG-0001",
                "WRONG_AMOUNT", "Consumidor questiona os juros da parcela (cenário fictício)", 8, false));
        target(seed, 0, 0, 9).ifPresent(t -> complaint(compliance, operador, t, "RECLAME_AQUI", prefix + "-RA-0001",
                "CONTACT_FREQUENCY", "Consumidor reclama de mensagens repetidas (cenário fictício)", 5, true));
    }

    private void complaint(Actor compliance, Actor operador, Target t, String channel, String protocol, String subject,
                           String summary, int daysAgo, boolean answered) {
        rt.optional("reclamação " + channel, () -> {
            boolean exists = rt.as(compliance, () -> complaints.search(new ComplaintQuery(null, Set.of("RECEIVED",
                    "IN_PROGRESS", "ANSWERED", "CLOSED"), Set.of(channel), null, null, null, false, null, protocol),
                    PageQuery.of(0, 1))).totalElements() > 0;
            if (exists) {
                return;
            }
            ComplaintView created = rt.as(operador, () -> complaints.register(new RegisterComplaintCommand(t.creditorId(),
                    t.debtorId(), t.caseId(), channel, protocol, subject, summary,
                    rt.now().minus(Duration.ofDays(daysAgo)), null, null)));
            if (answered) {
                rt.as(compliance, () -> complaints.start(created.id()));
                rt.as(compliance, () -> complaints.answer(created.id(),
                        "Contatos ajustados e consumidor orientado (cenário fictício)", null));
            }
        });
    }

    private Optional<Target> target(TenantSeed seed, int creditor, int portfolio, int index) {
        var debt = seed.debt(creditor, portfolio, index);
        UUID portfolioId = seed.portfolioId(creditor, portfolio);
        return rt.system(seed.tenantId, () -> debtors.findByDocument(debt.debtor().document())
                .flatMap(d -> cases.findActive(d.id(), portfolioId).map((CaseView c) -> new Target(d.id(),
                        seed.creditorId(creditor), c.id()))));
    }
}
