package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.TermContent;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementInstallment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementPayment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class AgreementService implements AgreementUseCases {

    static final String RESOURCE = "negotiation_agreement";

    private final AgreementRepository agreements;
    private final AgreementRecorder recorder;
    private final NegotiationViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final FileStorage fileStorage;
    private final DebtTitleUseCases titles;
    private final JsonCodec json;
    private final Clock clock;

    AgreementService(AgreementRepository agreements, AgreementRecorder recorder, NegotiationViews views,
                     AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger, FileStorage fileStorage,
                     DebtTitleUseCases titles, JsonCodec json, Clock clock) {
        this.agreements = agreements;
        this.recorder = recorder;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.fileStorage = fileStorage;
        this.titles = titles;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AgreementView get(UUID agreementId) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        return views.agreement(loadVisible(agreementId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AgreementView> search(AgreementFilter filter, PageQuery page) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        if (filter.creditorId() != null) {
            accessScope.requireCreditor(filter.creditorId());
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        AgreementStatus status = filter.status() == null ? null
                : ProposalService.enumValue(AgreementStatus.class, filter.status(), "agreement.status_invalid");
        return PageResponse.of(agreements.search(allowed, filter.creditorId(), filter.debtorId(), filter.caseId(), status,
                page.toPageable(Sort.by(Sort.Direction.DESC, "formalizedAt").and(Sort.by("id")))), views::agreement);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentView> payments(UUID agreementId) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Agreement a = loadVisible(agreementId);
        return agreements.findPayments(a.getId()).stream().map(NegotiationViews::payment).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentDistributionView> paymentDistribution(UUID agreementId, UUID paymentId) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Agreement a = loadVisible(agreementId);
        Optional<AgreementPayment> found = agreements.findPayment(a.getId(), paymentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AgreementPayment p = found.get();
        Map<UUID, Integer> numbers = a.getInstallments().stream()
                .collect(Collectors.toMap(AgreementInstallment::getId, AgreementInstallment::getNumber));
        List<InstallmentShareView> installments = new ArrayList<>();
        LedgerLines.allocations(json, p.getInstallmentAllocations()).forEach((id, value) ->
                installments.add(new InstallmentShareView(id, numbers.getOrDefault(id, 0), value.amount())));
        Map<String, Money> byComponent = new LinkedHashMap<>();
        Map<String, UUID> titleOf = new HashMap<>();
        for (Agreement.BucketPosting posting : LedgerLines.postings(json, p.getLedgerPostings())) {
            if (posting.payment().isPositive()) {
                String key = posting.titleId() + "|" + posting.part().name();
                byComponent.merge(key, posting.payment(), Money::plus);
                titleOf.put(key, posting.titleId());
            }
        }
        Map<UUID, String> references = titleOf.values().stream().distinct()
                .collect(Collectors.toMap(Function.identity(), id -> reference(titles.get(id))));
        List<ComponentShareView> components = byComponent.entrySet().stream()
                .map(e -> new ComponentShareView(titleOf.get(e.getKey()), references.get(titleOf.get(e.getKey())),
                        e.getKey().substring(e.getKey().indexOf('|') + 1), e.getValue().amount()))
                .toList();
        return Optional.of(new PaymentDistributionView(a.getId(), p.getPaymentId(), a.getCreditorId(), a.getDebtorId(),
                a.getPortfolioId(), p.getStatus().name(), p.getAmount().amount(), p.getAppliedAmount().amount(),
                p.getPaidAt(), p.getSource(), p.getMethod(), a.getInstallmentCount(), a.getMaxDaysOverdue(),
                a.getRenegotiationNumber(), a.getReplacedAgreementId(), installments, components));
    }

    private static String reference(TitleView t) {
        if (t.externalId() == null) {
            return null;
        }
        return t.installmentNumber() == null ? t.externalId() : t.externalId() + "/" + t.installmentNumber();
    }

    @Override
    public AgreementView cancel(UUID agreementId, String reason) {
        accessScope.requirePermission(Permission.AGREEMENT_CANCEL);
        Agreement visible = loadVisible(agreementId);
        accessScope.requireCreditor(visible.getCreditorId());
        Agreement a = agreements.findByIdForUpdate(visible.getId()).orElseThrow();
        Instant now = Instant.now(clock);
        Agreement.Ending ending = a.cancel(reason, LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)), now);
        agreements.save(a);
        recorder.statusChanged(a, ending.change(), now);
        ending.installmentChanges().forEach(c -> recorder.installmentChanged(a, c, now));
        return views.agreement(a);
    }

    @Override
    @Transactional(readOnly = true)
    public TermContent openTerm(UUID agreementId) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Agreement a = loadVisible(agreementId);
        if (a.getAcceptance() == null || a.getTermDeletedAt() != null) {
            throw new ResourceNotFoundException("agreement_term", agreementId);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(), Map.of("resource", RESOURCE,
                "resourceId", agreementId.toString(), "category", FileCategory.AGREEMENT_TERM.name())));
        return new TermContent("termo-acordo-" + a.getProposalId() + ".pdf", "application/pdf",
                a.getAcceptance().getTermSize() == null ? -1 : a.getAcceptance().getTermSize(),
                a.getAcceptance().getTermSha256(), fileStorage.open(a.getAcceptance().getTermFileKey()));
    }

    private Agreement loadVisible(UUID id) {
        Agreement a = agreements.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, a.getCreditorId());
        Actor actor = currentActor.get();
        if (actor.isAssistant() && !(actor.id().equals(a.getCloserId()) || actor.id().equals(a.getOriginatorId()))) {
            throw new ResourceNotFoundException(RESOURCE, id);
        }
        return a;
    }
}
