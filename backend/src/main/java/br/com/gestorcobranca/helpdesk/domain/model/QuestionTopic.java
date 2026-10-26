package br.com.gestorcobranca.helpdesk.domain.model;

/** Tema da pergunta, informado pela IA em {@code registrar_pergunta} (catálogo fechado). */
public enum QuestionTopic {
    /** como usar uma tela ou executar uma tarefa */
    HOW_TO,
    /** consulta de dados (situação de acordo, pagamento, importação…) */
    DATA_LOOKUP,
    /** regra de negócio (por que o sistema fez algo) */
    BUSINESS_RULE,
    /** acesso, perfil ou permissão */
    ACCESS,
    /** erro ou comportamento inesperado do sistema */
    SYSTEM_ERROR,
    /** fora do escopo do sistema */
    OUT_OF_SCOPE,
    OTHER
}
