package br.com.gestorcobranca.communication.application.port.in;

import java.util.UUID;

/**
 * Simulação de provedor para ambiente local e testes (provedor {@code SANDBOX}). Disponível apenas
 * com {@code app.communication.sandbox.enabled=true}, que é recusado no perfil {@code prod}.
 */
public interface SandboxUseCases {

    /** Simula uma mensagem recebida do contato na conta sandbox. */
    void simulateInbound(UUID channelAccountId, String from, String text);
}
