package br.com.gestorcobranca.debt.domain.model;

/** Tipo de processo de insolvência da empresa devedora (Lei 11.101/2005; cobrança B2B §7). */
public enum InsolvencyKind {
    /** Recuperação judicial (art. 47 e seguintes): suspende as execuções e a cobrança fica no plano. */
    JUDICIAL_RECOVERY,
    /** Recuperação extrajudicial homologada em juízo (art. 161 e seguintes). */
    EXTRAJUDICIAL_RECOVERY,
    /** Falência decretada (art. 75 e seguintes): o crédito é habilitado no processo. */
    BANKRUPTCY
}
