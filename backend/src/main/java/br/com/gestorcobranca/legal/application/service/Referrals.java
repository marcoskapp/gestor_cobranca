package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.legal.application.service.CaseFactsReader.TitleSnapshot;
import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.model.MatterOrigin;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator.Outcome;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Indicação de um caso de cobrança ao jurídico, comum à indicação manual, à varredura diária
 * pelas regras e ao alerta de prescrição próxima: avaliação de elegibilidade (com a política de
 * títulos prescritos da assessoria), um único caso jurídico aberto por caso de cobrança, janela
 * sem nova indicação automática após um encerramento, e gravação pelo {@link MatterRecorder}.
 */
@Component
class Referrals {

    /** Status do caso de cobrança que admitem indicação manual (a automática não indica com promessa pendente). */
    static final Set<String> MANUAL_STATUSES = Set.of("OPEN", "IN_NEGOTIATION", "PROMISE");
    static final Set<String> AUTOMATIC_STATUSES = Set.of("OPEN", "IN_NEGOTIATION");

    private final LegalMatterRepository matters;
    private final CaseFactsReader facts;
    private final LegalSettingsProvider settings;
    private final MatterRecorder recorder;
    private final LegalMetrics metrics;
    private final Clock clock;

    Referrals(LegalMatterRepository matters, CaseFactsReader facts, LegalSettingsProvider settings,
              MatterRecorder recorder, LegalMetrics metrics, Clock clock) {
        this.matters = matters;
        this.facts = facts;
        this.settings = settings;
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
    }

    Outcome evaluate(CaseView c, List<TitleSnapshot> snapshots, LegalEligibilityRule rule, Set<String> statuses) {
        LegalSettings.Values values = settings.current();
        return EligibilityEvaluator.evaluate(facts.eligibilityFacts(c, snapshots), rule, values.prescribedTitlePolicy(),
                statuses, LegalViews.today(clock), values.prescriptionWarningDays());
    }

    LegalMatter referManually(CaseView c, Collection<UUID> requestedTitles, String note) {
        if (matters.findOpenByCollectionCaseId(c.id()).isPresent()) {
            throw new ConflictException("legal_matter.already_open", "O caso de cobrança já tem caso jurídico aberto");
        }
        List<TitleSnapshot> snapshots = facts.openTitlesOf(c);
        Outcome outcome = evaluate(c, snapshots, null, MANUAL_STATUSES);
        record(outcome);
        if (!outcome.eligible()) {
            throw new BusinessRuleException("legal_matter.not_eligible",
                    "Caso de cobrança não elegível ao jurídico: " + String.join(", ", outcome.unmet()));
        }
        Set<UUID> chosen = requestedTitles == null || requestedTitles.isEmpty() ? outcome.titleIds()
                : Set.copyOf(requestedTitles);
        if (!outcome.titleIds().containsAll(chosen)) {
            throw new BusinessRuleException("legal_matter.title_not_eligible",
                    "Há títulos que não podem ser indicados (fora do caso, contestados, prescritos ou sem saldo)");
        }
        return create(c, snapshots, outcome, chosen, null, MatterOrigin.MANUAL, note, "manual");
    }

    /**
     * Indicação automática pelas regras ativas aplicáveis (a primeira que o caso atende).
     *
     * @return o caso jurídico criado, se algum critério foi atendido
     */
    Optional<LegalMatter> referAutomatically(CaseView c, List<LegalEligibilityRule> rules, MatterOrigin origin) {
        if (rules.isEmpty() || matters.findOpenByCollectionCaseId(c.id()).isPresent()) {
            metrics.eligibility(rules.isEmpty() ? "no_rule" : "already_open");
            return Optional.empty();
        }
        int cooldown = settings.current().referralCooldownDays();
        Optional<Instant> lastClosed = matters.lastClosedAtByCollectionCaseId(c.id());
        if (lastClosed.isPresent() && lastClosed.get().plus(Duration.ofDays(cooldown)).isAfter(Instant.now(clock))) {
            metrics.eligibility("cooldown");
            return Optional.empty();
        }
        if (!AUTOMATIC_STATUSES.contains(c.status())) {
            metrics.eligibility("case_status");
            return Optional.empty();
        }
        List<TitleSnapshot> snapshots = facts.openTitlesOf(c);
        for (LegalEligibilityRule rule : rules) {
            Outcome outcome = evaluate(c, snapshots, rule, AUTOMATIC_STATUSES);
            record(outcome);
            if (outcome.eligible()) {
                return Optional.of(create(c, snapshots, outcome, outcome.titleIds(), rule, origin, null,
                        origin == MatterOrigin.RULE ? "rule" : "prescription_alert"));
            }
        }
        return Optional.empty();
    }

    private LegalMatter create(CaseView c, List<TitleSnapshot> snapshots, Outcome outcome, Set<UUID> chosen,
                               LegalEligibilityRule rule, MatterOrigin origin, String note, String cause) {
        Money debt = snapshots.stream().filter(s -> chosen.contains(s.title().id()))
                .map(s -> Money.of(s.title().openBalance())).reduce(Money.ZERO, Money::plus);
        LegalMatter matter = new LegalMatter(c.id(), c.debtorId(), c.creditorId(), c.portfolioId(), chosen, origin,
                rule == null ? null : rule.getId(), note, debt, outcome.flags(), Instant.now(clock));
        List<String> excluded = new ArrayList<>();
        outcome.prescribedExcluded().forEach(id -> excluded.add(id + ":PRESCRIBED"));
        outcome.disputedExcluded().forEach(id -> excluded.add(id + ":DISPUTED"));
        return recorder.referred(matter, excluded, cause);
    }

    private void record(Outcome outcome) {
        metrics.eligibility(outcome.eligible() ? "eligible" : "ineligible");
        outcome.unmet().forEach(metrics::eligibilityReason);
    }
}
