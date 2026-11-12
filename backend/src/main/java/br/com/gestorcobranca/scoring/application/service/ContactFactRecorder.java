package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.Episode;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.OutcomeRow;
import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import br.com.gestorcobranca.scoring.domain.model.ContactChannel;
import br.com.gestorcobranca.scoring.domain.model.ContactFact;
import br.com.gestorcobranca.scoring.domain.model.ContactFact.Direction;
import br.com.gestorcobranca.scoring.domain.model.ContactFact.Kind;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fatos da inteligência de contato a partir dos eventos de integração (na transação do consumo idempotente) e
 * recálculo do perfil do devedor. Regras do desenho §2:
 * <ul>
 *   <li>ligação só pelo {@code CallCompleted}, mensagem só pelo {@code MessageStatusChanged}; o
 *       {@code InteractionRecorded} entra só com origem MANUAL ou SYSTEM (TELEPHONY e COMMUNICATION espelham os
 *       eventos próprios, e ASSISTANT registra promessa ou retorno dentro de uma conversa cuja mensagem já entrou
 *       como resposta — contar de novo seria contar em dobro);</li>
 *   <li>contato informado precisa ser do devedor do evento (defesa em profundidade);</li>
 *   <li>sem base legal registrada no score, nada é coletado.</li>
 * </ul>
 * Só ids, canal, hora e dia da semana no fuso de operação, contagens e situação — nunca o valor do contato.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ContactFactRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(ContactFactRecorder.class);

    /** Origens de interação contadas. */
    static final Set<String> COUNTED_SOURCES = Set.of("MANUAL", "SYSTEM");
    /**
     * Origens que espelham um fato já contado: ligação ({@code CallCompleted}), mensagem ({@code MessageStatusChanged})
     * e o assistente, que só registra promessa ou retorno dentro de uma conversa cuja mensagem já entrou como resposta.
     */
    static final Set<String> MIRRORED_SOURCES = Set.of("TELEPHONY", "COMMUNICATION", "ASSISTANT");
    /** Situações finais do desfecho (no mesmo instante, vencem a situação em vigor). */
    private static final Set<String> FINAL_STATUSES = Set.of("COMPLETED", "BROKEN", "CANCELLED");

    private final ContactFactStore store;
    private final ScoringSettingsProvider settings;
    private final DebtorUseCases debtors;
    private final DebtorContactUseCases contacts;
    private final ScoringLocks locks;
    private final ScoringMetrics metrics;
    private final Clock clock;

    ContactFactRecorder(ContactFactStore store, ScoringSettingsProvider settings, DebtorUseCases debtors,
                        DebtorContactUseCases contacts, ScoringLocks locks, ScoringMetrics metrics, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.debtors = debtors;
        this.contacts = contacts;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
    }

    void interaction(InteractionRecorded e) {
        if (e.source() == null) {
            metrics.contactFactIgnored("unknown_source");
            return;
        }
        if (MIRRORED_SOURCES.contains(e.source())) {
            metrics.contactFactIgnored("mirrored");
            return;
        }
        if (!COUNTED_SOURCES.contains(e.source())) {
            metrics.contactFactIgnored("unknown_source");
            return;
        }
        if ("OUTBOUND".equals(e.direction())) {
            record(e.eventId(), Kind.ATTEMPT, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    ContactChannel.of(e.channel()), Direction.OUTBOUND, e.effectiveContact(), e.occurredAt());
        } else if ("INBOUND".equals(e.direction())) {
            record(e.eventId(), Kind.RESPONSE, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    ContactChannel.of(e.channel()), Direction.INBOUND, true, e.occurredAt());
        } else {
            metrics.contactFactIgnored("not_applicable");
        }
    }

    void call(CallCompleted e) {
        if (e.debtorId() == null) {
            metrics.contactFactIgnored("not_applicable");
            return;
        }
        if ("OUTBOUND".equals(e.direction())) {
            boolean answered = CallCompleted.ANSWERED.equals(e.result()) && e.durationSeconds() > 0;
            record(e.eventId(), Kind.ATTEMPT, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    ContactChannel.PHONE, Direction.OUTBOUND, answered, e.occurredAt());
        } else if ("INBOUND".equals(e.direction())) {
            record(e.eventId(), Kind.RESPONSE, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    ContactChannel.PHONE, Direction.INBOUND, true, e.occurredAt());
        } else {
            metrics.contactFactIgnored("not_applicable");
        }
    }

    /** Mensagem enviada (tentativa), lida (sinal fraco) ou recebida (resposta); demais situações não geram fato. */
    void message(MessageStatusChanged e) {
        if (e.debtorId() == null) {
            return;
        }
        ContactChannel channel = ContactChannel.of(e.channel());
        if ("OUTBOUND".equals(e.direction()) && "SENT".equals(e.status())) {
            record(e.eventId(), Kind.ATTEMPT, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    channel, Direction.OUTBOUND, false, e.occurredAt());
        } else if ("OUTBOUND".equals(e.direction()) && "READ".equals(e.status())) {
            record(e.eventId(), Kind.READ, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    channel, Direction.OUTBOUND, false, e.occurredAt());
        } else if ("INBOUND".equals(e.direction()) && "RECEIVED".equals(e.status())) {
            record(e.eventId(), Kind.RESPONSE, e.debtorId(), e.creditorId(), e.portfolioId(), e.caseId(), e.contactId(),
                    channel, Direction.INBOUND, true, e.occurredAt());
        }
    }

    /**
     * Desfecho por acordo: na formalização grava as tentativas e contatos efetivos antes do acordo (desde o acordo
     * anterior do devedor no credor); nas mudanças seguintes, atualiza a situação final (ver {@link #supersedes}) e
     * recalcula as contagens com os fatos que chegaram depois. Acordo cujo primeiro evento visto não é a formalização
     * (formalizado antes da base legal ou da implantação) não ganha desfecho: as contagens "antes do acordo" e o
     * instante do acordo seriam os de outro evento.
     */
    void agreement(AgreementStatusChanged e) {
        if (e.debtorId() == null || e.creditorId() == null || e.agreementId() == null) {
            return;
        }
        String status = finalStatus(e.newStatus(), e.previousStatus());
        if (status == null) {
            metrics.contactFactIgnored("not_applicable");
            return;
        }
        if (!settings.enabled()) {
            metrics.contactFactIgnored("no_legal_basis");
            return;
        }
        locks.lockDebtor(e.debtorId());
        Instant now = Instant.now(clock);
        Optional<OutcomeRow> existing = store.findOutcome(e.agreementId());
        if (existing.isEmpty()) {
            if (!"FORMALIZED".equals(e.newStatus())) {
                metrics.contactFactIgnored("agreement_not_tracked");
                return;
            }
            Episode episode = store.episode(e.debtorId(), e.creditorId(), e.agreementId(), e.occurredAt());
            store.insertOutcome(new OutcomeRow(Ids.newId(), e.agreementId(), e.caseId(), e.debtorId(), e.creditorId(),
                    e.portfolioId(), personType(e.debtorId()), e.occurredAt(), episode, status, e.occurredAt(), now));
            return;
        }
        OutcomeRow row = existing.get();
        if (row.debtorId() == null) {
            return; // desfecho já sem identificação (anonimização ou retenção): fica como estatística
        }
        boolean newer = supersedes(e.occurredAt(), status, row.statusAt(), row.finalStatus());
        Episode episode = store.episode(row.debtorId(), row.creditorId(), row.agreementId(), row.agreedAt());
        store.updateOutcome(new OutcomeRow(row.id(), row.agreementId(), e.caseId(), row.debtorId(), row.creditorId(),
                e.portfolioId(), row.personType(), row.agreedAt(), episode, newer ? status : row.finalStatus(),
                newer ? e.occurredAt() : row.statusAt(), now));
    }

    /** LGPD: apaga fatos e perfis do devedor; desfechos de acordo ficam sem identificação (estatística). */
    void debtorAnonymized(DebtorAnonymized e) {
        locks.lockDebtor(e.debtorId());
        ContactFactStore.Removal removed = store.deleteByDebtor(e.debtorId(), TimeConfig.BUSINESS_ZONE);
        LOG.atInfo().addKeyValue("contactFacts", removed.facts()).addKeyValue("contactProfiles", removed.profiles())
                .addKeyValue("agreementOutcomes", removed.outcomes())
                .log("Inteligência de contato neutralizada na anonimização do devedor");
    }

    /** Recalcula o perfil do devedor a partir dos fatos vigentes (chamar com o lock do devedor). */
    void rebuild(UUID debtorId, String trigger) {
        List<ContactProfileRow> rows = ContactProfileCalculator.aggregate(debtorId, store.factsOfDebtor(debtorId));
        store.replaceProfiles(debtorId, rows, Instant.now(clock));
        metrics.contactProfileRebuilt(trigger);
    }

    /**
     * O evento substitui a situação gravada? Compara os instantes em microssegundos (a precisão do banco); no mesmo
     * instante — IN_PAYMENT e SETTLED do mesmo pagamento chegam juntos e em ordem não garantida — a situação final
     * (COMPLETED, BROKEN, CANCELLED) vence a em vigor (ACTIVE); entre duas do mesmo tipo, vale a que chegou depois.
     */
    static boolean supersedes(Instant eventAt, String eventStatus, Instant recordedAt, String recordedStatus) {
        int order = eventAt.truncatedTo(ChronoUnit.MICROS).compareTo(recordedAt.truncatedTo(ChronoUnit.MICROS));
        if (order != 0) {
            return order > 0;
        }
        return FINAL_STATUSES.contains(eventStatus) || !FINAL_STATUSES.contains(recordedStatus);
    }

    /**
     * Situação final do desfecho: em vigor (formalizado, em pagamento), cumprido (quitado), quebrado ou cancelado.
     * Renegociação de um acordo quebrado continua quebrado; de um acordo em vigor, cancelado (substituído sem
     * cumprimento — o novo acordo tem o próprio desfecho).
     */
    static String finalStatus(String newStatus, String previousStatus) {
        if (newStatus == null) {
            return null;
        }
        return switch (newStatus) {
            case "FORMALIZED", "IN_PAYMENT" -> "ACTIVE";
            case "SETTLED" -> "COMPLETED";
            case "BROKEN" -> "BROKEN";
            case "CANCELLED" -> "CANCELLED";
            case "RENEGOTIATED" -> "BROKEN".equals(previousStatus) ? "BROKEN" : "CANCELLED";
            default -> null;
        };
    }

    private void record(UUID eventId, Kind kind, UUID debtorId, UUID creditorId, UUID portfolioId, UUID caseId,
                        UUID contactId, ContactChannel channel, Direction direction, boolean success, Instant at) {
        if (!settings.enabled()) {
            metrics.contactFactIgnored("no_legal_basis");
            return;
        }
        Optional<DebtorContactUseCases.ContactView> contact = contactId == null ? Optional.empty()
                : contactOfDebtor(debtorId, contactId);
        if (contactId != null && contact.isEmpty()) {
            metrics.contactFactIgnored("contact_mismatch");
            LOG.atWarn().addKeyValue("eventId", eventId).addKeyValue("debtorId", debtorId)
                    .log("Evento com contato que não é do devedor: fora da inteligência de contato");
            return;
        }
        ContactFact fact = ContactFact.of(eventId, kind, debtorId, creditorId, portfolioId, caseId, contactId, channel,
                direction, success, personType(debtorId), at, TimeConfig.BUSINESS_ZONE);
        locks.lockDebtor(debtorId);
        if (!store.record(fact, contact.map(ContactFactRecorder::origin).orElse(null))) {
            metrics.contactFactIgnored("duplicate");
            return;
        }
        metrics.contactFact(kind.name(), channel.name(), contactId != null);
        if (kind == Kind.RESPONSE && channel.isMessage()) {
            store.markLatestAttemptAnswered(debtorId, creditorId, contactId, channel,
                    at.minus(ContactProfileCalculator.REPLY_WINDOW), at, eventId);
        }
        rebuild(debtorId, "event");
    }

    /** O contato do devedor (vazio se for de outro devedor: o debt o trata como inexistente). */
    private Optional<DebtorContactUseCases.ContactView> contactOfDebtor(UUID debtorId, UUID contactId) {
        try {
            return contacts.list(debtorId).stream().filter(c -> contactId.equals(c.id())).findFirst();
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * NEW: o contato chegou numa carga depois do cadastro do devedor (teve janela de novidade, ainda que encerrada —
     * contatos-recebidos.md); EXISTING: os demais.
     */
    static String origin(DebtorContactUseCases.ContactView contact) {
        return contact.receipt() != null && contact.receipt().noveltyUntil() != null ? "NEW" : "EXISTING";
    }

    private String personType(UUID debtorId) {
        try {
            return debtors.get(debtorId).personType();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }
}
