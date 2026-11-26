package br.com.gestorcobranca.debt.application.port.out;

import java.util.UUID;

/** Consulta escalar, sob a RLS do tenant corrente, sem carregar entidades. */
public interface DebtorCreditorLinks {

    /** O devedor tem título no credor ou é coobrigado ativo de contrato do credor. */
    boolean linked(UUID creditorId, UUID debtorId);
}
