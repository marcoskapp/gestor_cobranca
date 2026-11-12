package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.debt.domain.model.ContactNoveltySettings;
import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.ContactNoveltySettingsRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.debt.DebtorContactNoveltyChanged;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fim da novidade dos contatos de um devedor (docs/tecnico/desenho/contatos-recebidos.md §3): o maior
 * {@code novelty_until} entre os contatos novos ainda sem tentativa (e utilizáveis), com os tipos pendentes. Quem altera
 * um contato novo (carga, uso, invalidação, varredura) compara o estado antes e depois e publica
 * {@link DebtorContactNoveltyChanged} só quando muda. Executa na transação do chamador.
 *
 * <p>Quem recalcula a novidade depois de alterar um contato chama antes {@link #lockDebtor}: sem o bloqueio, usos
 * concorrentes de contatos diferentes do mesmo devedor leem o mesmo "antes" e publicam um valor que não reflete o
 * outro uso, e o consumidor grava o valor do evento (§9).
 */
@Component
class ContactNoveltyTracker {

    private final DebtorContactRepository contacts;
    private final DebtorRepository debtors;
    private final ContactNoveltySettingsRepository settings;
    private final DebtProperties properties;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;

    ContactNoveltyTracker(DebtorContactRepository contacts, DebtorRepository debtors,
                          ContactNoveltySettingsRepository settings, DebtProperties properties,
                          ApplicationEventPublisher events, CurrentActor currentActor) {
        this.contacts = contacts;
        this.debtors = debtors;
        this.settings = settings;
        this.properties = properties;
        this.events = events;
        this.currentActor = currentActor;
    }

    /** Janela de novidade da assessoria corrente (configurada ou padrão do sistema). */
    Duration window() {
        return Duration.ofDays(windowDays());
    }

    int windowDays() {
        return settings.findCurrent().map(ContactNoveltySettings::getWindowDays).orElseGet(this::defaultWindowDays);
    }

    int defaultWindowDays() {
        return properties.contactNovelty().windowDays();
    }

    /**
     * Bloqueia a linha do devedor até o fim da transação ({@code select … for update}), serializando o recálculo da
     * novidade dos contatos dele. Chamar antes de ler o estado "antes" e de tomar o instante do recálculo.
     */
    void lockDebtor(UUID debtorId) {
        debtors.findByIdForUpdate(debtorId);
    }

    /** Novidade pendente do devedor agora. */
    Pending pending(UUID debtorId, Instant now) {
        return pending(contacts.findByDebtorIdAndNoveltyUntilIsNotNull(debtorId), now);
    }

    /** Novidade pendente de vários devedores (ausente no mapa = nenhuma). */
    Map<UUID, Pending> pending(Collection<UUID> debtorIds, Instant now) {
        if (debtorIds.isEmpty()) {
            return Map.of();
        }
        return contacts.findByDebtorIdInAndNoveltyUntilIsNotNull(debtorIds).stream()
                .collect(Collectors.groupingBy(DebtorContact::getDebtorId)).entrySet().stream()
                .map(e -> Map.entry(e.getKey(), pending(e.getValue(), now)))
                .filter(e -> e.getValue().until() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Pending pending(List<DebtorContact> debtorContacts, Instant now) {
        Instant until = null;
        TreeSet<String> channels = new TreeSet<>();
        for (DebtorContact c : debtorContacts) {
            if (!c.isNoveltyPending(now)) {
                continue;
            }
            if (until == null || c.getNoveltyUntil().isAfter(until)) {
                until = c.getNoveltyUntil();
            }
            channels.add(c.getType().name());
            if (c.getType() == ContactType.PHONE && c.isWhatsapp()) {
                channels.add("WHATSAPP");
            }
        }
        return new Pending(until, List.copyOf(channels));
    }

    /**
     * Publica {@link DebtorContactNoveltyChanged} se o fim da novidade ou os tipos pendentes mudaram.
     *
     * @return true se publicou
     */
    boolean publishIfChanged(UUID debtorId, Pending before, Pending after, UUID importBatchId, Instant now) {
        if (before.equals(after)) {
            return false;
        }
        publish(debtorId, after, importBatchId, now);
        return true;
    }

    /** Publica a novidade atual do devedor (varredura: republicação idempotente). */
    void publish(UUID debtorId, Pending current, UUID importBatchId, Instant now) {
        events.publishEvent(new DebtorContactNoveltyChanged(Ids.newId(), currentActor.tenantId(), now, debtorId,
                current.until(), current.channels(), importBatchId));
    }

    /**
     * @param until    fim da novidade (nulo = nenhum contato novo pendente)
     * @param channels tipos com contato novo pendente (PHONE, EMAIL, ADDRESS, WHATSAPP)
     */
    record Pending(Instant until, List<String> channels) {

        static final Pending NONE = new Pending(null, List.of());

        Pending {
            channels = until == null ? List.of() : List.copyOf(channels);
        }
    }
}
