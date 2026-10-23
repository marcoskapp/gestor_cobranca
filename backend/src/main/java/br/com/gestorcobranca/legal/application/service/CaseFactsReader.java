package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleSearch;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases.ContractView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.ClaimView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.PrescriptionView;
import br.com.gestorcobranca.legal.domain.repository.AgreementRecordRepository;
import br.com.gestorcobranca.legal.domain.repository.CadenceExhaustionRepository;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator;
import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lê, pelas portas públicas do debt, os fatos de títulos de um caso de cobrança usados na
 * elegibilidade e na viabilidade: saldo e atraso, contrato de origem (natureza executiva),
 * garantias que cobrem cada título e prescrição por pretensão. Os acordos quebrados e as réguas
 * extrajudiciais esgotadas vêm de registros próprios alimentados pelos eventos do negotiation e do
 * campaign.
 */
@Component
class CaseFactsReader {

    private static final String EXECUTION = "EXECUTION";
    private static final String COLLECTION = "COLLECTION";
    private static final String PRESCRIBED = "PRESCRIBED";
    private static final int PAGE = 200;

    private final DebtTitleUseCases titles;
    private final OriginContractUseCases contracts;
    private final CollateralUseCases collaterals;
    private final TitlePrescriptionUseCases prescriptions;
    private final AgreementRecordRepository agreements;
    private final CadenceExhaustionRepository exhaustions;

    CaseFactsReader(DebtTitleUseCases titles, OriginContractUseCases contracts, CollateralUseCases collaterals,
                    TitlePrescriptionUseCases prescriptions, AgreementRecordRepository agreements,
                    CadenceExhaustionRepository exhaustions) {
        this.titles = titles;
        this.contracts = contracts;
        this.collaterals = collaterals;
        this.prescriptions = prescriptions;
        this.agreements = agreements;
        this.exhaustions = exhaustions;
    }

    /** Título com contrato, garantias que o cobrem e prescrição. */
    record TitleSnapshot(TitleView title, ContractView contract, List<CollateralView> collaterals,
                         PrescriptionView prescription) {

        Optional<ClaimView> claim(String name) {
            return prescription == null ? Optional.empty()
                    : prescription.claims().stream().filter(c -> c.claim().equals(name)).findFirst();
        }

        boolean executive() {
            return contract != null && contract.executiveTitle();
        }

        boolean prescribed(String claimName) {
            return claim(claimName).map(c -> PRESCRIBED.equals(c.situation())).orElse(false);
        }
    }

    /** Títulos em cobrança (OPEN) do devedor na carteira do caso. */
    List<TitleSnapshot> openTitlesOf(CaseView c) {
        List<TitleView> found = new ArrayList<>();
        int page = 0;
        List<TitleView> content;
        do {
            content = titles.search(new TitleSearch(c.creditorId(), c.portfolioId(), c.debtorId(), "OPEN", null, null,
                    null, null), new PageQuery(page++, PAGE)).content();
            found.addAll(content);
        } while (content.size() == PAGE);
        return snapshots(found);
    }

    /** Títulos informados (situação atual, qualquer status). */
    List<TitleSnapshot> titles(Collection<UUID> titleIds) {
        return snapshots(titleIds.stream().sorted().map(titles::get).toList());
    }

    private List<TitleSnapshot> snapshots(List<TitleView> list) {
        Map<UUID, ContractView> contractCache = new HashMap<>();
        Map<UUID, List<CollateralView>> collateralCache = new HashMap<>();
        List<TitleSnapshot> result = new ArrayList<>();
        for (TitleView t : list.stream().sorted(Comparator.comparing(TitleView::dueDate).thenComparing(TitleView::id))
                .toList()) {
            ContractView contract = null;
            List<CollateralView> covering = List.of();
            if (t.originContractId() != null) {
                contract = contractCache.computeIfAbsent(t.originContractId(), contracts::get);
                covering = collateralCache.computeIfAbsent(t.originContractId(), collaterals::listByContract).stream()
                        .filter(k -> k.titleIds().isEmpty() || k.titleIds().contains(t.id()))
                        .toList();
            }
            result.add(new TitleSnapshot(t, contract, covering, prescriptions.get(t.id())));
        }
        return result;
    }

    int brokenAgreements(UUID collectionCaseId) {
        return (int) agreements.countByCollectionCaseIdAndBrokenTrue(collectionCaseId);
    }

    EligibilityEvaluator.CaseFacts eligibilityFacts(CaseView c, List<TitleSnapshot> snapshots) {
        List<EligibilityEvaluator.TitleFacts> facts = snapshots.stream().map(s -> {
            TitleView t = s.title();
            LocalDate nearest = s.prescription() == null ? null : s.prescription().claims().stream()
                    .filter(k -> !PRESCRIBED.equals(k.situation()))
                    .filter(k -> !EXECUTION.equals(k.claim()) || s.executive())
                    .map(ClaimView::estimatedDate).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            boolean hasRule = s.prescription() != null && !s.prescription().claims().isEmpty();
            boolean validated = hasRule && s.prescription().claims().stream().allMatch(ClaimView::ruleValidated);
            return new EligibilityEvaluator.TitleFacts(t.id(), Money.ofNullable(t.openBalance()), t.daysOverdue(),
                    t.status(), t.disputeBlocked(), !s.collaterals().isEmpty(), s.executive(), s.prescribed(EXECUTION),
                    s.prescribed(COLLECTION), nearest, hasRule, validated);
        }).toList();
        return new EligibilityEvaluator.CaseFacts(c.id(), c.status(), facts, brokenAgreements(c.id()),
                exhaustions.findFirstByCollectionCaseIdOrderByExhaustedAtDesc(c.id()).isPresent());
    }

    List<ViabilityCalculator.TitleFact> viabilityTitles(List<TitleSnapshot> snapshots) {
        return snapshots.stream().filter(s -> s.title().openBalance() != null
                        && s.title().openBalance().signum() > 0)
                .map(s -> {
                    boolean hasRule = s.prescription() != null && !s.prescription().claims().isEmpty();
                    boolean validated = hasRule && s.prescription().claims().stream().allMatch(ClaimView::ruleValidated);
                    return new ViabilityCalculator.TitleFact(s.title().id(), Money.of(s.title().openBalance()),
                            s.executive(), s.claim(EXECUTION).map(ClaimView::estimatedDate).orElse(null),
                            s.prescribed(EXECUTION), s.claim(COLLECTION).map(ClaimView::estimatedDate).orElse(null),
                            s.prescribed(COLLECTION), hasRule, validated);
                }).toList();
    }

    /** Garantias distintas que cobrem os títulos. */
    List<CollateralView> collateralsOf(List<TitleSnapshot> snapshots) {
        Map<UUID, CollateralView> distinct = new LinkedHashMap<>();
        snapshots.forEach(s -> s.collaterals().forEach(k -> distinct.putIfAbsent(k.id(), k)));
        return List.copyOf(distinct.values());
    }
}
