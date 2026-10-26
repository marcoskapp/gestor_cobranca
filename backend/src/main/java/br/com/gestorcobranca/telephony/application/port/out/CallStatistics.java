package br.com.gestorcobranca.telephony.application.port.out;

import java.util.List;

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

    /**
     * Indicadores por conta de telefonia no período (comparação entre provedores), somente chamadas
     * de saída que chegaram ao provedor ou falharam nele.
     *
     * @param dialed          chamadas aceitas pelo provedor
     * @param rejected        recusadas pelo provedor antes de discar ({@code FAILED} sem início de discagem e sem porteiro)
     * @param answered        atendidas (pessoa ou secretária)
     * @param connected       conectadas a um operador
     * @param failed          falhas depois de aceitas ({@code FAILED} com início de discagem)
     * @param fallbackIn      discadas por esta conta como contingência de outra
     * @param billableSeconds atendimento → fim (base da tarifa por minuto)
     */
    List<AccountCounters> accountCounters(Instant from, Instant to);

    record AccountCounters(UUID accountId, long dialed, long rejected, long answered, long connected, long failed,
                           long fallbackIn, long ringSeconds, long ringEnded, long talkSeconds, long billableSeconds) {
    }

    record Counters(long dialed, long answered, long live, long connected, long abandoned, long machine,
                    long talkSeconds, long ringSeconds, long ended, long wrapUpSeconds, long tabulated) {

        public static final Counters EMPTY = new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
