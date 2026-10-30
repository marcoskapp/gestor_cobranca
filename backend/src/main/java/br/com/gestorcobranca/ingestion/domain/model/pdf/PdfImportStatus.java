package br.com.gestorcobranca.ingestion.domain.model.pdf;

/**
 * Ciclo de vida de uma importação PDF.
 * <pre>
 * RECEIVED ─job─► MAPPING ─► AWAITING_VALIDATION ─aprovação─► APPROVED (lote canônico na conferência do ingestion)
 *                         └─► AWAITING_MAP (sem mapa: IA desligada ou indisponível; o usuário escreve o mapa)
 *                         └─► FAILED (sem camada de texto e sem leitura visual disponível)
 * qualquer estado aberto ─descartar─► DISCARDED
 * </pre>
 */
public enum PdfImportStatus {
    RECEIVED,
    MAPPING,
    AWAITING_MAP,
    AWAITING_VALIDATION,
    APPROVED,
    DISCARDED,
    FAILED;

    public boolean open() {
        return this == RECEIVED || this == MAPPING || this == AWAITING_MAP || this == AWAITING_VALIDATION;
    }

    /** Aceita edição do mapa e decisões sobre os títulos. */
    public boolean editable() {
        return this == AWAITING_MAP || this == AWAITING_VALIDATION;
    }
}
