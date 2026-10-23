package br.com.gestorcobranca.communication.domain.model;

/**
 * Tipo de uma variável de template. Os tipos limitam o que pode entrar numa mensagem: não há
 * tipo para documento, telefone, e-mail ou endereço, e textos livres são verificados contra esses
 * padrões (minimização de dados pessoais — só o necessário para a comunicação).
 */
public enum VariableType {
    /** Primeiro nome do destinatário (uma palavra, até 30 letras). */
    FIRST_NAME,
    /** Valor monetário (ponto decimal), exibido como {@code R$ 1.234,56}. */
    MONEY,
    /** Data ISO ({@code 2026-10-23}), exibida como {@code 23/10/2026}. */
    DATE,
    /** Número inteiro (parcelas, dias). */
    INTEGER,
    /** Endereço {@code https://} (link de pagamento ou do portal). */
    URL,
    /** Código alfanumérico curto (protocolo, linha digitável, código Pix copia-e-cola truncado). */
    CODE,
    /** Texto curto sem dados pessoais (nome do credor, descrição de produto). */
    TEXT
}
