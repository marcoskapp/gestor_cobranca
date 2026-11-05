package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.AmountDivergenceStatus;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.TitleAmountDivergence;
import br.com.gestorcobranca.debt.domain.repository.TitleAmountDivergenceRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Carga do credor sobre título com principal corrigido manualmente: a correção é mantida e a divergência fica aberta
 * (uma por título) para alguém decidir; a mesma divergência repetida só conta ocorrências; valor novo substitui a
 * anterior; o credor passando a informar o valor corrigido encerra a divergência sozinho. Na transação da carga.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class AmountDivergenceRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(AmountDivergenceRecorder.class);

    private final TitleAmountDivergenceRepository divergences;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final DebtMetrics metrics;

    AmountDivergenceRecorder(TitleAmountDivergenceRepository divergences, AuditTrail auditTrail,
                             BusinessEventLogger businessEvents, DebtMetrics metrics) {
        this.divergences = divergences;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
    }

    /** @return true se uma divergência nova foi aberta (aviso da linha na carga) */
    boolean divergent(DebtTitle title, Money reported, UUID importBatchId, Instant now) {
        Optional<TitleAmountDivergence> open = divergences.findOpenByTitleId(title.getId());
        if (open.isPresent() && open.get().getReportedPrincipal().equals(reported)) {
            open.get().seenAgain(importBatchId, now);
            divergences.save(open.get());
            metrics.amountDivergence("seen_again");
            return false;
        }
        open.ifPresent(previous -> {
            previous.close(AmountDivergenceStatus.SUPERSEDED, null, null, null, now);
            divergences.save(previous);
        });
        TitleAmountDivergence created = divergences.save(new TitleAmountDivergence(title, reported, importBatchId, now));
        auditTrail.record(AuditEntry.of("title_amount.divergence_detected", TitleAmountAdjustmentService.TITLE, title.getId())
                .creditor(title.getCreditorId())
                .meta("divergenceId", created.getId())
                .meta("currentPrincipal", created.getCurrentPrincipal())
                .meta("reportedPrincipal", created.getReportedPrincipal())
                .meta("importBatchId", importBatchId));
        businessEvents.log(TitleAmountAdjustmentService.divergenceEvent(
                BusinessEventTypes.TITLE_AMOUNT_DIVERGENCE_DETECTED, title, created).attr("importBatchId", importBatchId));
        metrics.amountDivergence("detected");
        LOG.atInfo().addKeyValue("titleId", title.getId()).addKeyValue("divergenceId", created.getId())
                .log("Carga com principal diferente do corrigido manualmente: correção mantida, divergência aberta");
        return true;
    }

    /** O credor passou a informar o valor corrigido. */
    void converged(DebtTitle title, Instant now) {
        divergences.findOpenByTitleId(title.getId()).ifPresent(d -> {
            d.close(AmountDivergenceStatus.RESOLVED_BY_CREDITOR, null, null, null, now);
            divergences.save(d);
            businessEvents.log(TitleAmountAdjustmentService.divergenceEvent(
                    BusinessEventTypes.TITLE_AMOUNT_DIVERGENCE_RESOLVED, title, d).attr("decision", d.getStatus()));
            metrics.amountDivergence(d.getStatus().name());
        });
    }
}
