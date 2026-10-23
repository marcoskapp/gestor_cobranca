package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort.InterruptionResult;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort.LegalInterruption;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort.StoredEvidence;
import br.com.gestorcobranca.legal.domain.model.LegalDocument;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * Registro de interrupções da prescrição no módulo debt pela porta {@link PrescriptionInterruptionPort}
 * (idempotente por título × tipo × data): protesto lavrado (CC art. 202, III) e citação (CC art.
 * 202, I; CPC art. 240 §1º). A evidência, se houver, é o documento do caso jurídico já gravado no
 * armazenamento do tenant.
 */
@Component
class PrescriptionInterruptions {

    private final PrescriptionInterruptionPort port;
    private final LegalMetrics metrics;

    PrescriptionInterruptions(PrescriptionInterruptionPort port, LegalMetrics metrics) {
        this.port = port;
        this.metrics = metrics;
    }

    /** @return quantidade de interrupções novas (repetições idempotentes não contam) */
    int register(Collection<UUID> titleIds, String type, LocalDate date, String reference, UUID originReferenceId,
                 LegalDocument evidence) {
        StoredEvidence stored = evidence == null ? null : new StoredEvidence(evidence.getStorageKey(),
                evidence.getFileName(), evidence.getContentType(), evidence.getSizeBytes(), evidence.getSha256());
        int registered = 0;
        for (UUID titleId : titleIds.stream().sorted().toList()) {
            InterruptionResult result = port.register(new LegalInterruption(titleId, type, date, reference,
                    "Registrado pelo módulo jurídico", originReferenceId, stored));
            metrics.prescriptionInterruption(type, result.registered());
            if (result.registered()) {
                registered++;
            }
        }
        return registered;
    }
}
