package br.com.gestorcobranca.legal.domain.model;

/** Sinalizações do caso jurídico exibidas na triagem e nos painéis. */
public enum MatterFlag {
    /** Títulos com a pretensão de cobrança prescrita foram excluídos (política padrão). */
    PRESCRIBED_TITLES_EXCLUDED,
    /** Títulos prescritos foram mantidos (política FLAG da assessoria) — exige atenção jurídica. */
    PRESCRIBED_TITLES_INCLUDED,
    /** A pretensão executiva de algum título prescreveu: execução indisponível. */
    EXECUTION_PRESCRIBED,
    /** Prescrição estimada dentro da janela de atenção da assessoria. */
    PRESCRIPTION_NEAR,
    /** Regra de prescrição aplicada ainda pendente de validação jurídica. */
    PRESCRIPTION_RULE_UNVALIDATED,
    /** Títulos com contestação bloqueante foram excluídos. */
    DISPUTED_TITLES_EXCLUDED,
    /** Viabilidade aprovada com custo × benefício negativo (justificada no parecer). */
    NEGATIVE_COST_BENEFIT,
    /** Todos os títulos do caso saíram de cobrança (quitados, cancelados ou devolvidos). */
    TITLES_SETTLED
}
