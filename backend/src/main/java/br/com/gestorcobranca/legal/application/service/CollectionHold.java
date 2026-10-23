package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retenção LEGAL do caso de cobrança pela porta pública do collection
 * ({@link CaseUseCases#sendToLegal}/{@link CaseUseCases#returnFromLegal}), na transação do caso
 * jurídico: a partir da fase configurada ({@code collectionHoldStage}) o caso sai da operação
 * regular (réguas, filas e contato pausados pelo collection) e volta quando o caso jurídico
 * termina. Caso de cobrança já encerrado (ex.: títulos quitados) não é alterado.
 */
@Component
class CollectionHold {

    private static final Logger LOG = LoggerFactory.getLogger(CollectionHold.class);
    private static final String LEGAL = "LEGAL";
    private static final String CLOSED = "CLOSED";

    private final CaseUseCases cases;
    private final LegalMetrics metrics;

    CollectionHold(CaseUseCases cases, LegalMetrics metrics) {
        this.cases = cases;
        this.metrics = metrics;
    }

    /** Aplica a retenção que a fase atual pede (colocar ou retirar). */
    void sync(LegalMatter matter, LegalSettings.Values settings) {
        boolean wanted = !matter.getStage().isTerminal()
                && settings.collectionHoldStage().holds(matter.getStage());
        if (wanted && !matter.isCollectionHold()) {
            place(matter);
        } else if (!wanted && matter.isCollectionHold()) {
            release(matter);
        }
    }

    private void place(LegalMatter matter) {
        CaseView c = cases.get(matter.getCollectionCaseId());
        if (CLOSED.equals(c.status())) {
            metrics.collectionHold("place", "case_closed");
            return;
        }
        if (!LEGAL.equals(c.status())) {
            cases.sendToLegal(c.id(), "Caso jurídico em " + matter.getStage().name());
        }
        matter.markCollectionHold(true);
        metrics.collectionHold("place", LEGAL.equals(c.status()) ? "already_legal" : "placed");
        LOG.atInfo().addKeyValue("matterId", matter.getId()).addKeyValue("caseId", c.id())
                .log("Caso de cobrança retido no jurídico");
    }

    private void release(LegalMatter matter) {
        CaseView c = cases.get(matter.getCollectionCaseId());
        matter.markCollectionHold(false);
        if (!LEGAL.equals(c.status())) {
            metrics.collectionHold("release", CLOSED.equals(c.status()) ? "case_closed" : "not_legal");
            return;
        }
        cases.returnFromLegal(c.id(), "Caso jurídico " + matter.getStage().name());
        metrics.collectionHold("release", "released");
        LOG.atInfo().addKeyValue("matterId", matter.getId()).addKeyValue("caseId", c.id())
                .log("Caso de cobrança devolvido à operação regular");
    }
}
