package br.com.gestorcobranca.telephony.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Indicadores agregados das chamadas (leitura JDBC, tenant corrente). */
public interface CallStatistics {

    /**
     * Contadores da campanha desde o instante (início do dia de operação no ritmo do discador).
     *
     * @param dialed     chamadas que chegaram ao provedor
     * @param answered   atendidas (pessoa ou secretária)
     * @param live       atendidas por pessoa (conectadas + abandonadas) — denominador do abandono
     * @param connected  conectadas a um operador
     * @param abandoned  atendidas por pessoa sem operador
     * @param machine    secretária eletrônica (AMD)
     * @param talkSeconds soma da conversação das conectadas
     * @param ringSeconds soma do toque das discadas encerradas
     * @param wrapUpSeconds soma do pós-atendimento das tabuladas pelo operador
     * @param tabulated  tabuladas pelo operador (denominador do pós-atendimento médio)
     */
    Counters campaignCounters(UUID campaignId, Instant since);

    record Counters(long dialed, long answered, long live, long connected, long abandoned, long machine,
                    long talkSeconds, long ringSeconds, long ended, long wrapUpSeconds, long tabulated) {

        public static final Counters EMPTY = new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
