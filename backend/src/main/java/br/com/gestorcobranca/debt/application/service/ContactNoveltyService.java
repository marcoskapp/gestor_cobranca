package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.ContactNoveltyUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorSegmentationQueries;
import br.com.gestorcobranca.debt.domain.model.ContactNoveltySettings;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.ContactNoveltySettingsRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contatos recebidos na carga (docs/tecnico/desenho/contatos-recebidos.md): prazo da janela de novidade por assessoria,
 * uso do contato (consumidor {@code debt.contact-usage}), varredura diária da novidade vencida e atributos de
 * segmentação do devedor lidos pelo collection.
 */
@Service
@Transactional
class ContactNoveltyService implements ContactNoveltyUseCases, DebtorSegmentationQueries {

    /** Devedores por transação na varredura diária. */
    static final int SWEEP_BATCH = 200;

    private final ContactNoveltySettingsRepository settings;
    private final DebtorContactRepository contacts;
    private final DebtorRepository debtors;
    private final ContactNoveltyTracker novelty;
    private final DebtorVisibility visibility;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final DebtMetrics metrics;
    private final Clock clock;

    ContactNoveltyService(ContactNoveltySettingsRepository settings, DebtorContactRepository contacts,
                          DebtorRepository debtors, ContactNoveltyTracker novelty, DebtorVisibility visibility,
                          AccessScope accessScope, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                          DebtMetrics metrics, Clock clock) {
        this.settings = settings;
        this.contacts = contacts;
        this.debtors = debtors;
        this.novelty = novelty;
        this.visibility = visibility;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ prazo da assessoria

    @Override
    @Transactional(readOnly = true)
    public ContactNoveltySettingsView settings() {
        accessScope.requirePermission(Permission.DEBTOR_MANAGE);
        return view(settings.findCurrent());
    }

    @Override
    public ContactNoveltySettingsView updateSettings(int windowDays) {
        accessScope.requirePermission(Permission.DEBTOR_MANAGE);
        ContactNoveltySettings.requireValid(windowDays);
        Optional<ContactNoveltySettings> current = settings.findCurrent();
        int previous = current.map(ContactNoveltySettings::getWindowDays).orElseGet(novelty::defaultWindowDays);
        ContactNoveltySettings target;
        if (current.isEmpty()) {
            target = settings.save(new ContactNoveltySettings(windowDays));
        } else {
            target = current.get();
            if (!target.change(windowDays)) {
                return view(current);
            }
            target = settings.save(target);
        }
        auditTrail.record(AuditEntry.of("contact_novelty_settings.updated", "contact_novelty_settings", target.getId())
                .change("windowDays", previous, target.getWindowDays())
                .meta("customizedBefore", current.isPresent()));
        return view(Optional.of(target));
    }

    private ContactNoveltySettingsView view(Optional<ContactNoveltySettings> current) {
        int defaults = novelty.defaultWindowDays();
        return new ContactNoveltySettingsView(current.map(ContactNoveltySettings::getWindowDays).orElse(defaults), defaults,
                ContactNoveltySettings.MIN_WINDOW_DAYS, ContactNoveltySettings.MAX_WINDOW_DAYS, current.isPresent(),
                current.map(ContactNoveltySettings::getUpdatedAt).orElse(null));
    }

    // ------------------------------------------------------------------ segmentação (collection)

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, DebtorSegmentation> segmentation(Collection<UUID> debtorIds) {
        Guard.require(debtorIds != null && debtorIds.size() <= MAX_DEBTORS, "debtor.segmentation_too_many",
                "Até " + MAX_DEBTORS + " devedores por consulta");
        if (debtorIds.isEmpty()) {
            return Map.of();
        }
        List<Debtor> visible = visibility.filterVisible(debtors.findAllByIdIn(debtorIds));
        Instant now = Instant.now(clock);
        Map<UUID, ContactNoveltyTracker.Pending> pending = novelty.pending(visible.stream().map(Debtor::getId).toList(), now);
        Map<UUID, DebtorSegmentation> result = new LinkedHashMap<>();
        for (Debtor d : visible) {
            ContactNoveltyTracker.Pending p = pending.get(d.getId());
            result.put(d.getId(), new DebtorSegmentation(d.getId(), d.getPersonType().name(), p == null ? null : p.until()));
        }
        return result;
    }

    // ------------------------------------------------------------------ uso do contato (consumidor debt.contact-usage)

    /**
     * Uso de um contato do devedor (ligação, mensagem ou interação por ele): grava a primeira tentativa e o primeiro
     * contato efetivo uma única vez; a primeira tentativa por um contato novo encerra a novidade dele. Contato de outro
     * devedor é ignorado ({@code debt.contacts.usage_ignored{reason=contact_mismatch}}).
     *
     * @param channel canal do uso (PHONE, WHATSAPP, EMAIL, SMS…), só para o evento de negócio
     */
    void recordUse(UUID debtorId, UUID contactId, Instant at, boolean effective, String channel) {
        Optional<DebtorContact> found = contacts.findById(contactId);
        if (found.isEmpty()) {
            // contato removido (anonimização) depois do uso
            metrics.contactUsageIgnored("contact_not_found");
            return;
        }
        DebtorContact contact = found.get();
        if (!contact.getDebtorId().equals(debtorId)) {
            metrics.contactUsageIgnored("contact_mismatch");
            return;
        }
        ContactNoveltyTracker.Pending before = null;
        if (contact.isReceivedAfterRegistration()) {
            // serializa o recálculo por devedor; o instante vem depois do bloqueio (§9)
            novelty.lockDebtor(debtorId);
        }
        Instant now = Instant.now(clock);
        if (contact.isReceivedAfterRegistration()) {
            before = novelty.pending(debtorId, now);
        }
        boolean firstAttempt = contact.getFirstAttemptAt() == null;
        if (!contact.recordUse(at, effective)) {
            return;
        }
        contacts.save(contact);
        if (firstAttempt) {
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.CONTACT_FIRST_ATTEMPT).debtor(debtorId)
                    .reference("debtor_contact", contactId).channel(channel)
                    .attr("contactType", contact.getType())
                    .attr("receivedAfterRegistration", contact.isReceivedAfterRegistration())
                    .attr("effective", effective));
        }
        if (before != null) {
            novelty.publishIfChanged(debtorId, before, novelty.pending(debtorId, now), null, now);
        }
    }

    // ------------------------------------------------------------------ varredura diária

    /**
     * Um lote da varredura: devedores com janela de contato novo sem tentativa vencida em {@code (from, now]}, a partir
     * do cursor. Republica a novidade atual do devedor (a janela vencida deixa de valer: nulo, ou a de outro contato
     * novo ainda pendente); os consumidores aplicam o valor de forma idempotente. A busca para trás ({@code from})
     * cobre execuções perdidas; um devedor já republicado numa execução anterior recebe o mesmo valor de novo.
     *
     * @return cursor (último devedor do lote; nulo = fim) e quantidade de devedores republicados
     */
    SweepBatch sweepBatch(Instant from, UUID afterId) {
        Instant now = Instant.now(clock);
        List<UUID> debtorIds = contacts.findDebtorIdsWithNoveltyExpired(from, now, afterId, SWEEP_BATCH);
        for (UUID debtorId : debtorIds) {
            // mesmo bloqueio do uso do contato: a republicação não intercala com um recálculo em curso (§9)
            novelty.lockDebtor(debtorId);
            Instant at = Instant.now(clock);
            novelty.publish(debtorId, novelty.pending(debtorId, at), null, at);
        }
        metrics.noveltySweep("published", debtorIds.size());
        UUID cursor = debtorIds.size() < SWEEP_BATCH ? null : debtorIds.getLast();
        return new SweepBatch(cursor, debtorIds.size());
    }

    record SweepBatch(UUID cursor, int published) {
    }
}
