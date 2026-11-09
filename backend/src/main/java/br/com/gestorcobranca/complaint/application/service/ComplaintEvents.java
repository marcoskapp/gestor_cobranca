package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;

/**
 * Base dos eventos de negócio {@code complaint.*}: credor, devedor e caso (ids), referência, canal e assunto. Nunca o
 * resumo, a resposta nem o protocolo externo.
 */
final class ComplaintEvents {

    private ComplaintEvents() {
    }

    static BusinessEvent base(String type, Complaint c) {
        return BusinessEvent.of(type).creditor(c.getCreditorId()).debtor(c.getDebtorId()).collectionCase(c.getCaseId())
                .reference("complaint", c.getId()).channel(c.getChannel().name())
                .attr("subject", c.getSubject().name());
    }
}
