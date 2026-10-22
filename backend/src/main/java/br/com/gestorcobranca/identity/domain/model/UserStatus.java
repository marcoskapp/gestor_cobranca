package br.com.gestorcobranca.identity.domain.model;

public enum UserStatus {
    /** Criado, aguardando definição de senha pelo link de ativação. */
    PENDING_ACTIVATION,
    ACTIVE,
    /** Desativado por administrador: não autentica, sessões revogadas. */
    DISABLED
}
