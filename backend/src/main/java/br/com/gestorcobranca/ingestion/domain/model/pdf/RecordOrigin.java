package br.com.gestorcobranca.ingestion.domain.model.pdf;

/** Como os valores de um registro foram obtidos. */
public enum RecordOrigin {
    /** aplicação determinística do mapa à camada de texto do PDF */
    TEXT,
    /** leitura visual da página pelo modelo (PDF sem camada de texto): exige conferência reforçada */
    VISUAL
}
