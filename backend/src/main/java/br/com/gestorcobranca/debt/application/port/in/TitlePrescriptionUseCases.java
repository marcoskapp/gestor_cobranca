package br.com.gestorcobranca.debt.application.port.in;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prescrição estimada do título: data-base, prazo aplicado (regra do tenant para a natureza do
 * contrato), data estimada e interrupções (append-only). O registro de interrupção recalcula a
 * data estimada; a evidência opcional fica no armazenamento de arquivos do tenant e seu
 * download é registrado no log de acesso.
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
     * @param situation     NO_RULE (sem regra para a natureza), RUNNING (prazo correndo) ou
     *                      PRESCRIBED (data estimada já alcançada)
     * @param instrumentType natureza do contrato; nula para título sem contrato de origem
     * @param daysRemaining dias até a data estimada (negativo se já alcançada); nulo sem regra
     * @param lastAlertedFor data estimada para a qual já foi emitido alerta de prescrição próxima
     */
    record PrescriptionView(UUID titleId, String situation, String instrumentType, boolean executiveTitle, UUID ruleId,
                            LocalDate baseDate, LocalDate countingStart, Integer termYears, LocalDate estimatedDate,
                            Long daysRemaining, LocalDate lastAlertedFor, List<InterruptionView> interruptions) {
    }

    /**
     * @param effective se a interrupção reiniciou a contagem no cálculo vigente
     * @param origin    MANUAL ou LEGAL
     */
    record InterruptionView(UUID id, String type, LocalDate date, String reference, String notes, String origin,
                            UUID originReferenceId, boolean effective, EvidenceView evidence, Instant recordedAt,
                            UUID recordedBy) {
    }

    record EvidenceView(String fileName, String contentType, long size, String sha256) {
    }
}
