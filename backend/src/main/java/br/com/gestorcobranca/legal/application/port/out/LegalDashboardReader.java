package br.com.gestorcobranca.legal.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Leitura agregada do painel jurídico (consulta SQL; RLS do tenant corrente). */
public interface LegalDashboardReader {

    /**
     * @param creditorScope credores permitidos (nulo = todos)
     * @param lawFirmId     restrição de escritório (nulo = sem restrição)
     * @param creditorId    filtro de credor (opcional)
     */
    List<StageRow> stages(Set<UUID> creditorScope, UUID lawFirmId, UUID creditorId);

    record StageRow(String stage, long matters, BigDecimal debtAmount, BigDecimal expensesTotal,
                    BigDecimal recoveredAmount) {
    }
}
