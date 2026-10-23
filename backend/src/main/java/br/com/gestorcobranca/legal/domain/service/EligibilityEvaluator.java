package br.com.gestorcobranca.legal.domain.service;

import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.MatterFlag;
import br.com.gestorcobranca.legal.domain.model.PrescribedTitlePolicy;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Elegibilidade ao jurídico (etapa 1). Puro, sem dependências.
 * <ol>
 *   <li>O caso de cobrança precisa estar num status que admite indicação (sem contestação
 *       bloqueante, suspensão, acordo vigente nem jurídico).</li>
 *   <li>Títulos indicáveis: em cobrança (OPEN), com saldo e sem contestação bloqueante.</li>
 *   <li>Prescrição: com a política {@code EXCLUDE} (padrão), títulos com a pretensão de cobrança
 *       prescrita são excluídos e sinalizados; com {@code FLAG}, mantidos e sinalizados.</li>
 *   <li>Sem título indicável restante: inelegível.</li>
 *   <li>Regra (se houver): todos os critérios informados precisam ser atendidos.</li>
 * </ol>
 */
public final class EligibilityEvaluator {

    public static final String CASE_STATUS = "CASE_STATUS";
    public static final String NO_ELIGIBLE_TITLES = "NO_ELIGIBLE_TITLES";
    public static final String ALL_TITLES_PRESCRIBED = "ALL_TITLES_PRESCRIBED";
    public static final String MIN_DAYS_OVERDUE = "MIN_DAYS_OVERDUE";
    public static final String MIN_OPEN_BALANCE = "MIN_OPEN_BALANCE";
    public static final String COLLATERAL_REQUIRED = "COLLATERAL_REQUIRED";
    public static final String EXECUTIVE_TITLE_REQUIRED = "EXECUTIVE_TITLE_REQUIRED";
    public static final String MIN_BROKEN_AGREEMENTS = "MIN_BROKEN_AGREEMENTS";
    public static final String PRESCRIPTION_NOT_NEAR = "PRESCRIPTION_NOT_NEAR";

    private EligibilityEvaluator() {
    }

    /**
     * @param nearestEstimatedDate data estimada vigente (não prescrita) mais próxima entre as pretensões do título
     * @param hasRule              há regra de prescrição aplicável ao título
     */
    public record TitleFacts(UUID titleId, Money openBalance, long daysOverdue, String status, boolean disputeBlocked,
                             boolean hasCollateral, boolean executiveTitle, boolean executionPrescribed,
                             boolean collectionPrescribed, LocalDate nearestEstimatedDate, boolean hasRule,
                             boolean ruleValidated) {
    }

    public record CaseFacts(UUID caseId, String caseStatus, List<TitleFacts> titles, int brokenAgreements) {

        public CaseFacts {
            titles = List.copyOf(titles);
        }
    }

    /**
     * @param unmet         motivos de inelegibilidade (vazio = elegível)
     * @param titleIds      títulos que entram no caso jurídico
     * @param openBalance   saldo dos títulos que entram
     */
    public record Outcome(boolean eligible, List<String> unmet, Set<UUID> titleIds, Set<UUID> prescribedExcluded,
                          Set<UUID> disputedExcluded, Set<MatterFlag> flags, Money openBalance, long maxDaysOverdue) {
    }

    public static Outcome evaluate(CaseFacts facts, LegalEligibilityRule rule, PrescribedTitlePolicy policy,
                                   Set<String> acceptedCaseStatuses, LocalDate today, int warningDays) {
        List<String> unmet = new ArrayList<>();
        if (!acceptedCaseStatuses.contains(facts.caseStatus())) {
            unmet.add(CASE_STATUS);
        }
        Set<UUID> included = new LinkedHashSet<>();
        Set<UUID> prescribed = new LinkedHashSet<>();
        Set<UUID> disputed = new LinkedHashSet<>();
        Set<MatterFlag> flags = EnumSet.noneOf(MatterFlag.class);
        Money balance = Money.ZERO;
        long maxDays = 0;
        boolean collateral = false;
        boolean executive = false;
        LocalDate nearest = null;
        int candidates = 0;
        for (TitleFacts t : facts.titles()) {
            if (!"OPEN".equals(t.status()) || t.openBalance() == null || !t.openBalance().isPositive()) {
                continue;
            }
            if (t.disputeBlocked()) {
                disputed.add(t.titleId());
                flags.add(MatterFlag.DISPUTED_TITLES_EXCLUDED);
                continue;
            }
            candidates++;
            if (t.collectionPrescribed()) {
                if (policy == PrescribedTitlePolicy.EXCLUDE) {
                    prescribed.add(t.titleId());
                    flags.add(MatterFlag.PRESCRIBED_TITLES_EXCLUDED);
                    continue;
                }
                flags.add(MatterFlag.PRESCRIBED_TITLES_INCLUDED);
            }
            included.add(t.titleId());
            balance = balance.plus(t.openBalance());
            maxDays = Math.max(maxDays, t.daysOverdue());
            collateral |= t.hasCollateral();
            if (t.executiveTitle()) {
                if (t.executionPrescribed()) {
                    flags.add(MatterFlag.EXECUTION_PRESCRIBED);
                } else {
                    executive = true;
                }
            }
            if (t.hasRule() && !t.ruleValidated()) {
                flags.add(MatterFlag.PRESCRIPTION_RULE_UNVALIDATED);
            }
            if (!t.collectionPrescribed() && t.nearestEstimatedDate() != null
                    && (nearest == null || t.nearestEstimatedDate().isBefore(nearest))) {
                nearest = t.nearestEstimatedDate();
            }
        }
        if (nearest != null && daysUntil(today, nearest) <= warningDays) {
            flags.add(MatterFlag.PRESCRIPTION_NEAR);
        }
        if (included.isEmpty()) {
            unmet.add(candidates > 0 && prescribed.size() == candidates ? ALL_TITLES_PRESCRIBED : NO_ELIGIBLE_TITLES);
        } else if (rule != null) {
            if (rule.getMinDaysOverdue() != null && maxDays < rule.getMinDaysOverdue()) {
                unmet.add(MIN_DAYS_OVERDUE);
            }
            if (rule.getMinOpenBalance() != null && balance.isLessThan(rule.getMinOpenBalance())) {
                unmet.add(MIN_OPEN_BALANCE);
            }
            if (rule.isRequireCollateral() && !collateral) {
                unmet.add(COLLATERAL_REQUIRED);
            }
            if (rule.isRequireExecutiveTitle() && !executive) {
                unmet.add(EXECUTIVE_TITLE_REQUIRED);
            }
            if (rule.getMinBrokenAgreements() != null && facts.brokenAgreements() < rule.getMinBrokenAgreements()) {
                unmet.add(MIN_BROKEN_AGREEMENTS);
            }
            if (rule.getPrescriptionWithinDays() != null
                    && (nearest == null || daysUntil(today, nearest) > rule.getPrescriptionWithinDays())) {
                unmet.add(PRESCRIPTION_NOT_NEAR);
            }
        }
        return new Outcome(unmet.isEmpty(), List.copyOf(unmet), Set.copyOf(included), Set.copyOf(prescribed),
                Set.copyOf(disputed), flags.isEmpty() ? Set.of() : Set.copyOf(flags), balance, maxDays);
    }

    private static long daysUntil(LocalDate today, LocalDate date) {
        return ChronoUnit.DAYS.between(today, date);
    }
}
