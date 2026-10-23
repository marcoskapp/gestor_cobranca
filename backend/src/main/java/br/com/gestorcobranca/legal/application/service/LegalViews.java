package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.AuthorizationView;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.MeasureView;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases.HearingView;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementView;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineView;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.SuspensionView;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.DocumentView;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.RuleView;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases.ExpenseView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.StageEntryView;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentView;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ChecklistEntry;
import br.com.gestorcobranca.legal.domain.model.CaseMovement;
import br.com.gestorcobranca.legal.domain.model.CourtSuspension;
import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;
import br.com.gestorcobranca.legal.domain.model.ExtrajudicialMeasure;
import br.com.gestorcobranca.legal.domain.model.Hearing;
import br.com.gestorcobranca.legal.domain.model.LawFirm;
import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalDocument;
import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.LegalExpense;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.StageHistoryEntry;
import br.com.gestorcobranca.legal.domain.model.ViabilityAssessment;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Conversões entre o domínio e os records públicos, e utilitários comuns dos casos de uso. */
final class LegalViews {

    private LegalViews() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(code, label + " obrigatório(a)");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, label + " inválido(a): " + value);
        }
    }

    static <E extends Enum<E>> E parseOptional(Class<E> type, String value, String code, String label) {
        return value == null || value.isBlank() ? null : parse(type, value, code, label);
    }

    static <E extends Enum<E>> Set<E> parseSet(Class<E> type, Collection<String> values, String code, String label) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<E> set = EnumSet.noneOf(type);
        values.forEach(v -> set.add(parse(type, v, code, label)));
        return Set.copyOf(set);
    }

    static Set<String> names(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    static List<String> nameList(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).map(String::valueOf).toList();
    }

    /** Valor opcional: nulo continua nulo (diferente de {@code Money.ofNullable}, que devolve zero). */
    static Money money(BigDecimal amount) {
        return amount == null ? null : Money.of(amount);
    }

    static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }

    static BigDecimal value(Percentage percentage) {
        return percentage == null ? null : percentage.value();
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    static long version(Long version) {
        return version == null ? 0 : version;
    }

    static MatterView matter(LegalMatter m) {
        return new MatterView(m.getId(), m.getCollectionCaseId(), m.getDebtorId(), m.getCreditorId(), m.getPortfolioId(),
                m.getOrigin().name(), m.getEligibilityRuleId(), m.getReferralNote(), m.getTitleIds(), names(m.getFlags()),
                m.getStage().name(), name(m.getResumeStage()), m.getStageReason(), m.getStageChangedAt(),
                names(m.getStage().next()), name(m.getPrimaryRoute()), m.getResponsibleType().name(),
                m.getResponsibleUserId(), m.getLawFirmId(), amount(m.getDebtAmount()), amount(m.getExpectedCosts()),
                amount(m.getEstimatedRecoverable()), m.getAuthorizationId(), amount(m.getAuthorizedBudget()),
                amount(m.getExpensesTotal()), amount(m.getRecoveredAmount()), m.getAgreementId(), m.isCollectionHold(),
                m.getReferredAt(), m.getClosedAt(), name(m.getClosureReason()), version(m.getVersion()));
    }

    static StageEntryView stage(StageHistoryEntry e) {
        return new StageEntryView(e.getId(), name(e.getFromStage()), e.getToStage().name(), e.getReason(),
                name(e.getClosureReason()), e.getTrigger(), e.getOccurredAt(), e.getCreatedBy());
    }

    static RuleView rule(LegalEligibilityRule r) {
        return new RuleView(r.getId(), r.getCreditorId(), r.getPortfolioId(), r.getName(), r.isActive(),
                r.getMinDaysOverdue(), amount(r.getMinOpenBalance()), r.isRequireCollateral(), r.isRequireExecutiveTitle(),
                r.getMinBrokenAgreements(), r.getPrescriptionWithinDays(), version(r.getVersion()));
    }

    static AssessmentView assessment(ViabilityAssessment a) {
        return new AssessmentView(a.getId(), a.getMatterId(), amount(a.getDebtAmount()), amount(a.getCollectibleDebt()),
                amount(a.getCollateralValue()), amount(a.getCollateralRecoverable()), amount(a.getOtherAssetsValue()),
                value(a.getRealizationRate()), value(a.getSuccessProbability()), amount(a.getExpectedCosts()),
                amount(a.getEstimatedRecoverable()), amount(a.getNetBenefit()), a.getCostBenefitRatio(),
                a.isEconomicallyViable(), a.isViable(), nameList(a.getRecommendedRoutes()),
                nameList(a.getAvailableRoutes()), nameList(a.getSuggestedRoutes()), names(a.getFlags()),
                a.getChecklist().stream().map(i -> new ChecklistEntry(i.getCode(), i.getLabel(), i.getStatus().name(),
                        i.isRequired())).toList(),
                a.getAssetSearchNotes(), a.getOpinion(), a.getCreatedAt(), a.getCreatedBy());
    }

    static AuthorizationView authorization(CreditorAuthorization a) {
        return new AuthorizationView(a.getId(), a.getMatterId(), a.getStatus().name(), names(a.getRequestedRoutes()),
                amount(a.getRequestedBudget()), a.getRequestNote(), names(a.getAuthorizedRoutes()),
                amount(a.getApprovedBudget()), a.getDecisionDate(), name(a.getChannel()), a.getCreditorRepresentative(),
                a.getEvidenceDocumentId(), a.getDecisionNote(), a.getCreatedAt(), a.getCreatedBy(), a.getDecidedAt(),
                a.getDecidedBy());
    }

    static MeasureView measure(ExtrajudicialMeasure m) {
        return new MeasureView(m.getId(), m.getMatterId(), m.getType().name(), m.getTitleIds(), m.getCollateralId(),
                amount(m.getAmount()), m.getAuthority(), m.getProtocol(), m.getPriorNoticeOn(), m.getSubmittedOn(),
                m.getEffectiveOn(), m.getResolvedOn(), m.getDueDate(), m.getStatus().name(), m.getStatusReason(),
                m.getProvider(), m.getProviderReference(), m.getCreatedAt());
    }

    static LawsuitView lawsuit(Lawsuit l) {
        return new LawsuitView(l.getId(), l.getMatterId(), l.getCnjNumber(), l.cnj().formatted(), l.getCourtCode(),
                l.getCourtName(), l.getCourtUnit(), l.getRoute().name(), l.getActionClass(), l.getCreditorPole().name(),
                l.getDebtorIds(), amount(l.getClaimAmount()), l.getFiledOn(), l.getStatus().name(), l.getStatusReason(),
                l.getProvider(), l.getLastSyncedAt(), version(l.getVersion()));
    }

    static MovementView movement(CaseMovement m) {
        return new MovementView(m.getId(), m.getLawsuitId(), m.getOccurredOn(), m.getType().name(), m.getDescription(),
                m.getSource().name(), m.getProvider(), m.getExternalId(), amount(m.getAmount()), m.getCollateralId(),
                m.getCreatedAt(), m.getCreatedBy());
    }

    static DeadlineView deadline(LegalDeadline d, Integer businessDaysRemaining) {
        return new DeadlineView(d.getId(), d.getMatterId(), d.getLawsuitId(), d.getMovementId(), d.getMeasureId(),
                d.getKind().name(), d.getDescription(), d.getCourtCode(), d.getStartDate(), name(d.getStartEvent()),
                d.getTermDays(), d.getCountingMode().name(), d.getFixedDate(), d.getDueDate(), d.getResponsibleUserId(),
                d.getStatus().name(), d.getCompletedOn(), d.getStatusReason(), d.getLastAlertLevel(),
                businessDaysRemaining, d.getCreatedAt(), version(d.getVersion()));
    }

    static SuspensionView suspension(CourtSuspension s) {
        return new SuspensionView(s.getId(), s.getStartDate(), s.getEndDate(), s.getCourtCode(), s.getReason(),
                s.getCreatedAt());
    }

    static HearingView hearing(Hearing h) {
        return new HearingView(h.getId(), h.getLawsuitId(), h.getMatterId(), h.getType().name(), h.getScheduledAt(),
                h.getMode().name(), h.getLocation(), h.getLink(), h.getParticipantUserIds(), h.getExternalParticipants(),
                h.getStatus().name(), name(h.getOutcome()), h.getResultNotes(), h.getRescheduleCount(),
                version(h.getVersion()));
    }

    static ExpenseView expense(LegalExpense e) {
        return new ExpenseView(e.getId(), e.getMatterId(), e.getLawsuitId(), e.getType().name(), e.getDescription(),
                amount(e.getAmount()), e.getIncurredOn(), e.getAdvancedBy().name(), e.getLawFirmId(), e.isReimbursable(),
                e.getReceiptDocumentId(), e.isOverBudget(), e.getStatus().name(), e.getStatusDate(), e.getStatusReason(),
                e.getCreatedAt(), e.getCreatedBy());
    }

    static DocumentView document(LegalDocument d) {
        return new DocumentView(d.getId(), d.getMatterId(), d.getKind().name(), d.getLinkType().name(), d.getLinkId(),
                d.getDescription(), d.getFileName(), d.getContentType(), d.getSizeBytes(), d.getSha256(),
                d.getCreatedAt(), d.getCreatedBy());
    }

    static LawFirmView firm(LawFirm f) {
        Document cnpj = new Document(Document.DocumentType.CNPJ, f.getCnpj());
        return new LawFirmView(f.getId(), f.getName(), cnpj.masked(), cnpj.formatted(), f.getOabRegistration(),
                f.getContactEmail(), f.isActive(), version(f.getVersion()));
    }
}
