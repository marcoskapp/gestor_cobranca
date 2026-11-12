package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.AppliedRetention;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore;
import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Retenção da inteligência de contato (categoria {@code SCORING_CONTACT_FACTS}, por assessoria), em rodadas curtas:
 * cada rodada roda na transação de quem chama (o agendamento abre uma nova por rodada), apaga os fatos anteriores ao
 * prazo de até {@value #DEBTOR_BATCH} devedores — cada um sob o lock do devedor, com o perfil recalculado com os fatos
 * que ficam — e tira a identificação (acordo, caso, devedor; instantes truncados para o dia) de até
 * {@value #OUTCOME_BATCH} desfechos encerrados de acordos anteriores ao prazo. Assim nenhuma transação segura muitos
 * locks nem muitas linhas.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ContactRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(ContactRetentionService.class);
    /** Devedores por rodada (cada um com o próprio lock e recálculo do perfil). */
    static final int DEBTOR_BATCH = 200;
    /** Desfechos sem identificação por rodada. */
    static final int OUTCOME_BATCH = 1000;

    private final ContactFactStore store;
    private final ContactFactRecorder recorder;
    private final ScoringLocks locks;
    private final RetentionUseCases retention;
    private final ScoringMetrics metrics;
    private final Clock clock;

    ContactRetentionService(ContactFactStore store, ContactFactRecorder recorder, ScoringLocks locks,
                            RetentionUseCases retention, ScoringMetrics metrics, Clock clock) {
        this.store = store;
        this.recorder = recorder;
        this.locks = locks;
        this.retention = retention;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param facts    fatos apagados na rodada
     * @param debtors  devedores com o perfil recalculado
     * @param outcomes desfechos que ficaram sem identificação
     * @param more     rodada cheia: pode haver mais a descartar (repetir em nova transação)
     */
    record Round(int facts, int debtors, int outcomes, boolean more) {

        int removed() {
            return facts + outcomes;
        }
    }

    /** Uma rodada da retenção (chamar em transação própria, repetindo enquanto {@link Round#more()}). */
    Round purgeBatch() {
        AppliedRetention applied = retention.retentionFor(ScoringRetentionDefaults.CONTACT_FACTS)
                .orElseThrow(() -> new IllegalStateException("Retenção sem prazo para a categoria "
                        + ScoringRetentionDefaults.CONTACT_FACTS));
        Instant cutoff = applied.cutoff(Instant.now(clock));
        List<UUID> debtors = store.debtorsWithFactsBefore(cutoff, DEBTOR_BATCH); // ordem de id (locks em ordem)
        int facts = 0;
        for (UUID debtorId : debtors) {
            locks.lockDebtor(debtorId);
            facts += store.deleteFactsOfDebtorBefore(debtorId, cutoff);
            recorder.rebuild(debtorId, "retention");
        }
        int outcomes = store.neutralizeOutcomesBefore(cutoff, OUTCOME_BATCH, TimeConfig.BUSINESS_ZONE);
        metrics.purged("contact_facts", facts);
        metrics.purged("contact_outcomes", outcomes);
        if (facts + outcomes > 0) {
            LOG.atInfo().addKeyValue("process", "scoring.contact_retention").addKeyValue("facts", facts)
                    .addKeyValue("debtors", debtors.size()).addKeyValue("outcomes", outcomes)
                    .addKeyValue("days", applied.amount()).addKeyValue("source", applied.source())
                    .log("Retenção da inteligência de contato executada");
        }
        return new Round(facts, debtors.size(), outcomes,
                debtors.size() >= DEBTOR_BATCH || outcomes >= OUTCOME_BATCH);
    }
}
