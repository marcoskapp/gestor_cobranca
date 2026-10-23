package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.out.NegotiationPersonalData;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.negotiation.domain.repository.ProposalRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Anonimização LGPD do devedor no módulo (consumo de {@code DebtorAnonymized}, publicado pelo debt
 * quando o compliance anonimiza): apaga a observação livre das justificativas de concessão
 * (simulações e propostas), as observações de decisão, IP e agente de usuário dos aceites, o motivo
 * livre de cancelamento e remove os PDFs dos termos (que contêm o nome do devedor) após o commit. Ficam
 * os valores, códigos de motivo, o hash do termo e a trilha de auditoria.
 */
@Component
class NegotiationAnonymizer {

    private static final Logger LOG = LoggerFactory.getLogger(NegotiationAnonymizer.class);

    private final NegotiationPersonalData personalData;
    private final ProposalRepository proposals;
    private final AgreementRepository agreements;
    private final FileStorage fileStorage;
    private final AuditTrail auditTrail;
    private final NegotiationMetrics metrics;
    private final Clock clock;

    NegotiationAnonymizer(NegotiationPersonalData personalData, ProposalRepository proposals,
                          AgreementRepository agreements, FileStorage fileStorage, AuditTrail auditTrail,
                          NegotiationMetrics metrics, Clock clock) {
        this.personalData = personalData;
        this.proposals = proposals;
        this.agreements = agreements;
        this.fileStorage = fileStorage;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    void anonymize(UUID debtorId) {
        Instant now = Instant.now(clock);
        int simulations = personalData.anonymizeSimulations(debtorId);
        Set<String> termKeys = new HashSet<>();
        List<Proposal> debtorProposals = proposals.findByDebtorId(debtorId);
        for (Proposal p : debtorProposals) {
            if (p.getAcceptance() != null && p.getAcceptance().getTermFileKey() != null) {
                termKeys.add(p.getAcceptance().getTermFileKey());
            }
            p.anonymize();
            proposals.save(p);
        }
        List<Agreement> debtorAgreements = agreements.findByDebtorId(debtorId);
        for (Agreement a : debtorAgreements) {
            if (a.getAcceptance() != null && a.getAcceptance().getTermFileKey() != null) {
                termKeys.add(a.getAcceptance().getTermFileKey());
            }
            a.anonymize(now);
            agreements.save(a);
        }
        deleteAfterCommit(new ArrayList<>(termKeys));
        int total = simulations + debtorProposals.size() + debtorAgreements.size();
        auditTrail.record(AuditEntry.of("negotiation.debtor_anonymized", "debtor", debtorId)
                .meta("simulations", simulations).meta("proposals", debtorProposals.size())
                .meta("agreements", debtorAgreements.size()).meta("termsRemoved", termKeys.size()));
        metrics.anonymized(total);
    }

    private void deleteAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        fileStorage.delete(key);
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("storageKey", key).setCause(e)
                                .log("Termo de acordo de devedor anonimizado não pôde ser removido");
                    }
                }
            }
        });
    }
}
