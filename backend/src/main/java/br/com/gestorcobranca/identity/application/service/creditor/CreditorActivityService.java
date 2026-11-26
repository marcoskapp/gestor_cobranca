package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorActivityQueries;
import br.com.gestorcobranca.identity.application.port.out.CreditorActivityReader;
import br.com.gestorcobranca.identity.application.port.out.CreditorActivityReader.ActivityRow;
import br.com.gestorcobranca.identity.domain.service.CreditorActivityCatalog;
import br.com.gestorcobranca.identity.domain.service.CreditorActivityCatalog.Activity;
import br.com.gestorcobranca.identity.domain.service.CreditorActivityCursor;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Minha atividade" do portal do credor (fatia 7). A pessoa e o credor vêm do ator {@code CREDITOR} (outro tipo →
 * {@code denyActorType}, 403); a visão do time exige {@code CREDITOR_ACTIVITY_VIEW} e fica registrada no log de
 * acesso ({@code USER_DIRECTORY_QUERIED}). Só a lista branca de {@link CreditorActivityCatalog}.
 */
@Service
@Transactional(readOnly = true)
class CreditorActivityService implements CreditorActivityQueries {

    private static final String RESOURCE = "creditor_activity";
    /** Posição inicial do cursor (antes de qualquer id). */
    private static final UUID MAX_ID = new UUID(-1L, -1L);

    private final CreditorActivityReader reader;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;
    private final Clock clock;

    CreditorActivityService(CreditorActivityReader reader, CurrentActor currentActor, AccessScope accessScope,
                            AccessLogger accessLogger, Clock clock) {
        this.reader = reader;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    public CreditorActivityPage activity(boolean team, String before, int size) {
        Actor actor = currentActor.get();
        if (!actor.isCreditor()) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(team ? Permission.CREDITOR_ACTIVITY_VIEW : Permission.CREDITOR_PORTAL_OVERVIEW);
        Guard.require(size >= 1 && size <= MAX_PAGE_SIZE, "creditor_activity.size_invalid",
                "Itens por página: de 1 a %d".formatted(MAX_PAGE_SIZE));
        UUID creditorId = actor.creditorScope().creditorIds().iterator().next();
        Instant now = Instant.now(clock);
        Instant since = now.minus(Duration.ofDays(WINDOW_DAYS));
        CreditorActivityCursor cursor = before == null || before.isBlank() ? null : CreditorActivityCursor.decode(before);
        if (team) {
            // consulta à atividade de outras pessoas do credor: fica registrada (quem consultou e de onde)
            accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, actor,
                    Map.of("scope", "creditor_team", "origin", "creditor_portal")));
        }
        List<ActivityRow> rows = reader.read(creditorId, team ? null : actor.id(), CreditorActivityCatalog.auditActions(),
                CreditorActivityCatalog.accessTypes().stream().map(Enum::name).toList(),
                CreditorActivityCatalog.PORTAL_ORIGIN, since,
                cursor == null ? now.plus(Duration.ofDays(1)) : cursor.occurredAt(),
                cursor == null ? MAX_ID : cursor.id(), size + 1);
        List<CreditorActivityItem> items = new ArrayList<>();
        for (ActivityRow row : rows.subList(0, Math.min(size, rows.size()))) {
            activity(row).ifPresent(a -> items.add(new CreditorActivityItem(row.occurredAt(), a.name(),
                    row.personId().equals(actor.id()), row.personName(),
                    CreditorActivityCatalog.reference(row.entityType()).orElse(null),
                    CreditorActivityCatalog.reference(row.entityType()).isPresent() ? uuid(row.entityId()) : null)));
        }
        String next = rows.size() > size
                ? new CreditorActivityCursor(rows.get(size - 1).occurredAt(), rows.get(size - 1).id()).encode() : null;
        return new CreditorActivityPage(List.copyOf(items), next);
    }

    /** Defesa em profundidade: a consulta já filtra pela lista branca; aqui, só o que tem código. */
    private static Optional<Activity> activity(ActivityRow row) {
        if ("AUDIT".equals(row.source())) {
            return CreditorActivityCatalog.audit(row.code());
        }
        try {
            return CreditorActivityCatalog.access(AccessEventType.valueOf(row.code()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
