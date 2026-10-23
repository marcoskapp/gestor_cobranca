package br.com.gestorcobranca.debt.application.port.in;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prescrição estimada do título por pretensão (execução e cobrança): data-base, prazo
 * aplicado (regra do tenant para a natureza do contrato), data estimada e interrupções
 * (append-only). O registro de interrupção recalcula as datas estimadas; a evidência opcional
 * fica no armazenamento de arquivos do tenant e seu download é registrado no log de acesso.
 */
public interface TitlePrescriptionUseCases {

    PrescriptionView get(UUID titleId);

    /** Mesmo tipo de interrupção na mesma data → conflito {@code prescription_interruption.duplicate}. */
    PrescriptionView registerInterruption(UUID titleId, RegisterInterruptionCommand command, EvidenceUpload evidence);

    /** Conteúdo da evidência; o chamador fecha o stream. Registra {@code FILE_DOWNLOADED}. */
    EvidenceDownload evidence(UUID titleId, UUID interruptionId);

    /** @param type PROTEST, CITATION, ACKNOWLEDGEMENT ou OTHER */
    record RegisterInterruptionCommand(String type, LocalDate date, String reference, String notes) {
    }

    /** Arquivo enviado (PDF, JPEG ou PNG). */
    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record EvidenceDownload(String fileName, String contentType, long size, InputStream content) {
    }

    /**
     * @param instrumentType natureza do contrato; nula para título sem contrato de origem
     * @param claims         pretensões com regra aplicável (EXECUTION só para título executivo); vazia sem regra
     */
    record PrescriptionView(UUID titleId, UUID originContractId, String instrumentType, boolean executiveTitle,
                            List<ClaimView> claims, List<InterruptionView> interruptions) {
    }

    /**
     * @param situation      RUNNING (prazo correndo) ou PRESCRIBED (data estimada já alcançada)
     * @param ruleValidated  falso enquanto a regra semeada não for validada pelo jurídico
     * @param daysRemaining  dias até a data estimada (negativo se já alcançada)
     * @param lastAlertedFor data estimada para a qual já foi emitido alerta de prescrição próxima
     */
    record ClaimView(String claim, String situation, UUID ruleId, boolean ruleValidated, String legalBasis,
                     LocalDate baseDate, LocalDate countingStart, int termMonths, LocalDate estimatedDate,
                     long daysRemaining, LocalDate lastAlertedFor) {
    }

    /**
     * @param effectiveFor pretensões cuja contagem a interrupção reiniciou no cálculo vigente
     * @param origin       MANUAL ou LEGAL
     */
    record InterruptionView(UUID id, String type, LocalDate date, String reference, String notes, String origin,
                            UUID originReferenceId, List<String> effectiveFor, EvidenceView evidence,
                            Instant recordedAt, UUID recordedBy) {
    }

    record EvidenceView(String fileName, String contentType, long size, String sha256) {
    }
}
