package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.AssignmentView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.MailView;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeAssignment;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;
import br.com.gestorcobranca.collection.domain.model.DisputeDebtorNotice;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.model.DisputeMail;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Views de contestação com os dados mínimos dos títulos (consulta ao debt uma vez por devedor, respeitando o escopo do
 * ator), nomes de usuários (diretório do identity, só a própria assessoria, uma consulta por lista) e o histórico.
 */
@Component
class DisputeViews {

    private static final Comparator<DisputedTitle> BY_DUE = Comparator
            .comparing(DisputedTitle::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DisputedTitle::titleId);

    private final DisputeEvidenceRepository evidences;
    private final DebtTitleUseCases titles;
    private final UserDirectory users;

    DisputeViews(DisputeEvidenceRepository evidences, DebtTitleUseCases titles, UserDirectory users) {
        this.evidences = evidences;
        this.titles = titles;
        this.users = users;
    }

    DisputeView view(Dispute dispute) {
        return views(List.of(dispute)).getFirst();
    }

    List<DisputeView> views(List<Dispute> disputes) {
        Map<UUID, Map<UUID, DisputedTitle>> byDebtor = new HashMap<>();
        disputes.stream().map(Dispute::getDebtorId).distinct()
                .forEach(debtorId -> byDebtor.put(debtorId, titles.listByDebtor(debtorId).stream()
                        .collect(Collectors.toMap(TitleView::id, DisputeViews::title))));
        Map<UUID, String> names = names(disputes.stream().map(Dispute::getAssigneeId));
        return disputes.stream().map(d -> view(d, byDebtor.getOrDefault(d.getDebtorId(), Map.of()), names)).toList();
    }

    /** Com os títulos já carregados pelo chamador (ex.: a ficha do caso). */
    DisputeView view(Dispute dispute, Map<UUID, DisputedTitle> knownTitles) {
        return view(dispute, knownTitles, names(Stream.of(dispute.getAssigneeId())));
    }

    private DisputeView view(Dispute dispute, Map<UUID, DisputedTitle> knownTitles, Map<UUID, String> names) {
        List<DisputedTitle> disputed = dispute.getTitleIds().stream().map(knownTitles::get).filter(Objects::nonNull)
                .sorted(BY_DUE).toList();
        return CollectionViews.dispute(dispute, (int) evidences.countByDisputeId(dispute.getId()), disputed,
                dispute.getAssigneeId() == null ? null : names.get(dispute.getAssigneeId()));
    }

    List<EvidenceView> evidences(Collection<DisputeEvidence> list) {
        Map<UUID, String> names = names(list.stream().map(DisputeEvidence::getCreatedBy));
        return list.stream().map(e -> CollectionViews.evidence(e, e.getCreatedBy() == null ? null
                : names.get(e.getCreatedBy()))).toList();
    }

    List<AssignmentView> assignments(List<DisputeAssignment> list) {
        Map<UUID, String> names = names(list.stream().flatMap(a -> Stream.of(a.getAssigneeId(), a.getPreviousAssigneeId(),
                a.getAssignedBy())));
        return list.stream().map(a -> new AssignmentView(a.getId(), a.getPreviousAssigneeId(),
                names.get(a.getPreviousAssigneeId()), a.getAssigneeId(), names.get(a.getAssigneeId()), a.getMode().name(),
                a.getReason(), a.getAssignedBy(), names.get(a.getAssignedBy()), a.getAssignedAt())).toList();
    }

    List<CreditorRequestView> creditorRequests(List<DisputeCreditorRequest> list, List<DisputeMail> mails) {
        Map<UUID, List<DisputeMail>> byRequest = mails.stream().collect(Collectors.groupingBy(DisputeMail::getRequestId));
        Map<UUID, String> names = names(list.stream().map(DisputeCreditorRequest::getRequestedBy));
        return list.stream().map(r -> new CreditorRequestView(r.getId(), r.getDisputeId(), r.getSequenceNumber(),
                r.getTemplateKey(), r.getTemplateVersion(), r.getTo(), r.getCc(), r.getReplyTo(), r.getSubject(),
                r.getBody(), r.getRequestedDueDate(), r.getStatus().name(), r.isOverdue(), r.getSentAt(),
                r.getAnsweredAt(), r.getLinkExpiresAt(), r.getLinkDownloads(), r.getTitleIds(), r.getEvidenceIds(),
                r.getRequestedBy(), names.get(r.getRequestedBy()), r.getCreatedAt(),
                byRequest.getOrDefault(r.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(DisputeMail::getCreatedAt))
                        .map(m -> new MailView(m.getId(), m.getPurpose().name(), m.getStatus().name(), m.getAttempts(),
                                m.getSentAt(), m.getFailedAt(), m.getFailureCode()))
                        .toList()))
                .toList();
    }

    List<CreditorResponseView> creditorResponses(List<DisputeCreditorResponse> list, List<DisputeEvidence> disputeEvidence) {
        return creditorResponses(list, disputeEvidence, List.of());
    }

    /** @param quarantined documentos do credor pela API (em verificação ou recusados) */
    List<CreditorResponseView> creditorResponses(List<DisputeCreditorResponse> list, List<DisputeEvidence> disputeEvidence,
                                                 List<br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine> quarantined) {
        // anexos de e-mail (sem resposta) contam no e-mail, não na resposta
        quarantined = quarantined.stream().filter(q -> q.getCreditorResponseId() != null).toList();
        Map<UUID, Long> pending = quarantined.stream().filter(q -> q.isPending())
                .collect(Collectors.groupingBy(q -> q.getCreditorResponseId(), Collectors.counting()));
        Map<UUID, Long> rejected = quarantined.stream()
                .filter(q -> q.getStatus() == br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine.Status.INFECTED
                        || q.getStatus() == br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine.Status.FAILED)
                .collect(Collectors.groupingBy(q -> q.getCreditorResponseId(), Collectors.counting()));
        Map<UUID, List<UUID>> documents = disputeEvidence.stream().filter(e -> e.getCreditorResponseId() != null)
                .collect(Collectors.groupingBy(DisputeEvidence::getCreditorResponseId,
                        Collectors.mapping(DisputeEvidence::getId, Collectors.toList())));
        Map<UUID, String> names = names(list.stream().map(DisputeCreditorResponse::getRecordedBy));
        return list.stream().map(r -> new CreditorResponseView(r.getId(), r.getDisputeId(), r.getRequestId(),
                r.getSource().name(), r.getPosition().name(), r.getNote(), r.getReceivedAt(), r.getRecordedBy(),
                names.get(r.getRecordedBy()), documents.getOrDefault(r.getId(), List.of()),
                pending.getOrDefault(r.getId(), 0L).intValue(), rejected.getOrDefault(r.getId(), 0L).intValue())).toList();
    }

    List<DebtorNoticeView> debtorNotices(List<DisputeDebtorNotice> list) {
        Map<UUID, String> names = names(list.stream().map(DisputeDebtorNotice::getSentBy));
        return list.stream().map(n -> new DebtorNoticeView(n.getId(), n.getDisputeId(), n.getChannel().name(),
                n.getMessageId(), n.getStatus().name(), n.getFailureDetail(), n.getSubject(), n.getBody(), n.getSentBy(),
                names.get(n.getSentBy()), n.getCreatedAt(), n.getStatusAt())).toList();
    }

    private Map<UUID, String> names(Stream<UUID> ids) {
        Set<UUID> wanted = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (!wanted.isEmpty()) {
            users.findAll(wanted).forEach(u -> names.putIfAbsent(u.id(), u.name()));
        }
        return names;
    }

    static DisputedTitle title(TitleView t) {
        return new DisputedTitle(t.id(), t.externalId(), t.installmentNumber(), t.dueDate(), t.principalAmount(),
                t.openBalance(), t.status());
    }

    static Map<UUID, DisputedTitle> index(Collection<DisputedTitle> list) {
        return list.stream().collect(Collectors.toMap(DisputedTitle::titleId, Function.identity()));
    }
}
