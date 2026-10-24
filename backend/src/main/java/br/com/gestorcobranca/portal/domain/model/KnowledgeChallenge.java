package br.com.gestorcobranca.portal.domain.model;

/**
 * Desafio adicional de conhecimento, configurável por credor, exigido depois do código ou do link.
 * O dado nunca é exibido nem devolvido: o devedor informa e o servidor compara.
 */
public enum KnowledgeChallenge {
    /** Só o fator de posse (código no contato cadastrado ou link de uso único). */
    NONE,
    /** Data de nascimento do devedor (pessoa física). */
    BIRTH_DATE,
    /** Quatro últimos caracteres do número de um contrato (ou título) em cobrança no portal. */
    CONTRACT_SUFFIX
}
