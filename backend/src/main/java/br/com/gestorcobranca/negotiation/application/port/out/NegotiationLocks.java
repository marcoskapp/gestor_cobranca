package br.com.gestorcobranca.negotiation.application.port.out;

import java.util.UUID;

/** Bloqueios consultivos transacionais (liberados no fim da transação). */
public interface NegotiationLocks {

    /**
     * Serializa formalizações e renegociações do mesmo devedor (impede dois acordos simultâneos sobre
     * os mesmos títulos; o índice único parcial de títulos ativos é a última defesa).
     */
    void lockDebtor(UUID debtorId);
}
