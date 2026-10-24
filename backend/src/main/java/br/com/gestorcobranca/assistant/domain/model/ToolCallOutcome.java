package br.com.gestorcobranca.assistant.domain.model;

/** Resultado da execução de uma ferramenta pedida pelo modelo. */
public enum ToolCallOutcome {
    /** executada pelo caso de uso do módulo dono */
    OK,
    /** recusada pela validação do servidor (argumento inválido, valor fora do que o motor devolveu…) */
    REJECTED,
    /** o caso de uso do módulo dono recusou ou falhou */
    ERROR
}
