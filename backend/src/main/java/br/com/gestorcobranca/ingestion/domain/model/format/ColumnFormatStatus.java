package br.com.gestorcobranca.ingestion.domain.model.format;

/** Situação da convenção numérica de uma coluna após a leitura prévia ({@code importacao.md} §2.2). */
public enum ColumnFormatStatus {
    /** Só valores tipados ou sem separador: nada a decidir. */
    NOT_NEEDED,
    /** Evidência de uma só convenção, nenhum valor ambíguo. */
    DETECTED,
    /** Valores ambíguos resolvidos pela convenção que a própria coluna prova. */
    INFERRED,
    /** Só valores ambíguos (e sem separador): exige decisão. */
    AMBIGUOUS,
    /** Evidência das duas convenções na mesma coluna: exige decisão. */
    MIXED;

    public boolean requiresDecision() {
        return this == AMBIGUOUS || this == MIXED;
    }
}
