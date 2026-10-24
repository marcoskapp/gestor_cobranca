package br.com.gestorcobranca.scoring.application.port.out;

import java.util.Collection;
import java.util.UUID;

/** Locks consultivos de transação (PostgreSQL), por tenant. */
public interface ScoringLocks {

    /** Serializa uma operação da assessoria (criação de parâmetros, semeadura e versões de modelo). */
    void lockTenant(String scope);

    /** Serializa o cálculo dos casos (lote × sob demanda × evento); obtidos em ordem de id. */
    void lockCases(Collection<UUID> caseIds);
}
