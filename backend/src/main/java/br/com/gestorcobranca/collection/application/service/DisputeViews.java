package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Views de contestação com os dados mínimos dos títulos (consulta ao debt uma vez por devedor,
 * respeitando o escopo do ator) e evidências com o nome de quem anexou (diretório do identity,
 * só nomes da própria assessoria).
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
        return disputes.stream().map(d -> view(d, byDebtor.getOrDefault(d.getDebtorId(), Map.of()))).toList();
    }

    /** Com os títulos já carregados pelo chamador (ex.: a ficha do caso). */
    DisputeView view(Dispute dispute, Map<UUID, DisputedTitle> knownTitles) {
        List<DisputedTitle> disputed = dispute.getTitleIds().stream().map(knownTitles::get).filter(Objects::nonNull)
                .sorted(BY_DUE).toList();
        return CollectionViews.dispute(dispute, (int) evidences.countByDisputeId(dispute.getId()), disputed);
    }

    List<EvidenceView> evidences(Collection<DisputeEvidence> list) {
        Map<UUID, String> names = users.findAll(list.stream().map(DisputeEvidence::getCreatedBy).filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(UserSummary::id, UserSummary::name));
        return list.stream().map(e -> CollectionViews.evidence(e, e.getCreatedBy() == null ? null
                : names.get(e.getCreatedBy()))).toList();
    }

    static DisputedTitle title(TitleView t) {
        return new DisputedTitle(t.id(), t.externalId(), t.installmentNumber(), t.dueDate(), t.principalAmount(),
                t.openBalance());
    }

    static Map<UUID, DisputedTitle> index(Collection<DisputedTitle> list) {
        return list.stream().collect(Collectors.toMap(DisputedTitle::titleId, Function.identity()));
    }
}
