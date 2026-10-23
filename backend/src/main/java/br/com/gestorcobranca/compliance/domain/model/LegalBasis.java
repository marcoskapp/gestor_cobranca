package br.com.gestorcobranca.compliance.domain.model;

/** Bases legais para o tratamento de dados pessoais (LGPD art. 7º) aplicáveis à cobrança. */
public enum LegalBasis {
    /** Art. 7º, V — execução de contrato do qual o titular é parte. */
    EXECUCAO_CONTRATO,
    /** Art. 7º, VI — exercício regular de direitos em processo judicial, administrativo ou arbitral. */
    EXERCICIO_REGULAR_DIREITOS,
    /** Art. 7º, IX — legítimo interesse do controlador ou de terceiro. */
    LEGITIMO_INTERESSE,
    /** Art. 7º, X — proteção do crédito. */
    PROTECAO_CREDITO,
    /** Art. 7º, I — consentimento do titular. */
    CONSENTIMENTO,
    /** Art. 7º, II — cumprimento de obrigação legal ou regulatória. */
    OBRIGACAO_LEGAL
}
