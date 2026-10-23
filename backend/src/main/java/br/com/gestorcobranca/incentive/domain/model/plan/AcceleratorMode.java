package br.com.gestorcobranca.incentive.domain.model.plan;

/** Alcance do acelerador da meta. */
public enum AcceleratorMode {
    /** O fator multiplica todo o variável calculado. */
    TOTAL,
    /** O fator multiplica apenas a parcela do variável proporcional ao volume acima do limiar. */
    EXCESS
}
