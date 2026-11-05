package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;

/** Base dos eventos de negócio das contestações: credor, carteira, devedor, caso, contestação, tipo e origem (sem PII). */
final class DisputeEvents {

    private DisputeEvents() {
    }

    static BusinessEvent base(String type, Dispute d) {
        return BusinessEvent.of(type)
                .creditor(d.getCreditorId()).portfolio(d.getPortfolioId()).debtor(d.getDebtorId())
                .collectionCase(d.getCaseId())
                .reference("dispute", d.getId())
                .attr("type", d.getType())
                .attr("origin", d.getOrigin());
    }
}
