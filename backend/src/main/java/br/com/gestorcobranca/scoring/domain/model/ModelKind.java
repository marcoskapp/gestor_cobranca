package br.com.gestorcobranca.scoring.domain.model;

public enum ModelKind {
    /** Pontos por faixa de feature (padrão sem histórico; configurável). */
    SCORECARD,
    /** Regressão logística L2 treinada no histórico da assessoria. */
    LOGISTIC_REGRESSION
}
