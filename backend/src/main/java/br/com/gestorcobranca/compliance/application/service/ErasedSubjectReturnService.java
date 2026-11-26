package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ErasedSubjectReturnUseCases;
import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectRelease;
import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn;
import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn.Outcome;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import br.com.gestorcobranca.compliance.domain.repository.ErasedSubjectReleaseRepository;
import br.com.gestorcobranca.compliance.domain.repository.ErasedSubjectReturnRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class ErasedSubjectReturnService implements ErasedSubjectReturnUseCases {

    private static final String RESOURCE = "data_subject_request";

    private final DataSubjectRequestRepository requests;
    private final ErasedSubjectReturnRepository returns;
    private final ErasedSubjectReleaseRepository releases;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    ErasedSubjectReturnService(DataSubjectRequestRepository requests, ErasedSubjectReturnRepository returns,
                               ErasedSubjectReleaseRepository releases, AccessScope accessScope,
                               CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                               Clock clock) {
        this.requests = requests;
        this.returns = returns;
        this.releases = releases;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnsView of(UUID requestId) {
        return view(load(requestId));
    }

    @Override
    public ReturnsView keep(UUID requestId) {
        DataSubjectRequest request = load(requestId);
        List<ErasedSubjectReturn> pending = returns.findByRequestIdAndOutcomeIsNull(requestId);
        if (pending.isEmpty()) {
            throw new ConflictException("erased_subject_return.nothing_to_review", "Não há tentativa de retorno a revisar");
        }
        review(request, pending, Outcome.KEPT);
        return view(request);
    }

    @Override
    public ReturnsView release(UUID requestId, String reason) {
        DataSubjectRequest request = load(requestId);
        if (releases.findByRequestId(requestId).isPresent()) {
            throw new ConflictException("erased_subject_release.already_released", "O retorno do titular já foi liberado");
        }
        Instant now = Instant.now(clock);
        ErasedSubjectRelease release = releases.save(new ErasedSubjectRelease(request, reason, currentActor.actorId(), now));
        List<ErasedSubjectReturn> pending = returns.findByRequestIdAndOutcomeIsNull(requestId);
        auditTrail.record(AuditEntry.of("data_subject_request.erased_subject_released", RESOURCE, requestId)
                .reason(release.getReason())
                .meta("pendingReturns", pending.size()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.ERASED_SUBJECT_RETURN_REVIEWED)
                .attr("outcome", Outcome.RELEASED.name())
                .attr("returns", pending.size()));
        if (!pending.isEmpty()) {
            reviewWithoutEvent(pending, Outcome.RELEASED, now);
        }
        return view(request);
    }

    private void review(DataSubjectRequest request, List<ErasedSubjectReturn> pending, Outcome outcome) {
        Instant now = Instant.now(clock);
        reviewWithoutEvent(pending, outcome, now);
        auditTrail.record(AuditEntry.of("data_subject_request.erased_subject_return_kept", RESOURCE, request.getId())
                .meta("returns", pending.size()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.ERASED_SUBJECT_RETURN_REVIEWED)
                .attr("outcome", outcome.name())
                .attr("returns", pending.size()));
    }

    private void reviewWithoutEvent(List<ErasedSubjectReturn> pending, Outcome outcome, Instant now) {
        UUID reviewer = currentActor.actorId();
        pending.forEach(r -> r.review(outcome, reviewer, now));
        returns.saveAll(pending);
    }

    /** Mesma regra dos comandos das solicitações: todos os dados do titular, todos os credores. */
    private DataSubjectRequest load(UUID requestId) {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
        return requests.findById(requestId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, requestId));
    }

    private ReturnsView view(DataSubjectRequest request) {
        Optional<ErasedSubjectRelease> release = releases.findByRequestId(request.getId());
        boolean refusing = request.getErasedSubjectHash() != null && release.isEmpty();
        boolean releasable = refusing && request.getExecutedBy() != null
                && !Objects.equals(currentActor.actorId(), request.getExecutedBy());
        List<ReturnView> attempts = returns.findByRequestIdOrderByAttemptedAtDesc(request.getId()).stream()
                .map(r -> new ReturnView(r.getId(), r.getAttemptedAt(), r.getSource().name(), r.getCreditorId(),
                        r.getBatchId(), r.getLines(), r.getOutcome() == null ? null : r.getOutcome().name(),
                        r.getReviewedAt(), r.getReviewedBy()))
                .toList();
        return new ReturnsView(request.getId(), refusing, releasable, attempts,
                release.map(l -> new ReleaseView(l.getReleasedAt(), l.getReleasedBy(), l.getReason())).orElse(null));
    }
}
