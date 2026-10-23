package br.com.gestorcobranca.negotiation.application.port.out;

import java.util.UUID;

/** Operações de proteção de dados pessoais sobre registros append-only do módulo. */
public interface NegotiationPersonalData {

    /**
     * Apaga a observação livre da justificativa das simulações do devedor (coluna e memória de
     * cálculo em JSON); o código do motivo permanece. Devolve o número de simulações alteradas.
     */
    int anonymizeSimulations(UUID debtorId);
}
