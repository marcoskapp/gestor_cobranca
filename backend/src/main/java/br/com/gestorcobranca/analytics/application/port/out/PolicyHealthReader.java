package br.com.gestorcobranca.analytics.application.port.out;

import br.com.gestorcobranca.analytics.domain.model.PolicySegment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Leitura agregada para a saúde da política (views de contrato do analytics, com RLS de quem consulta). */
public interface PolicyHealthReader {

    /**
     * Acordos do credor formalizados no período, por carteira × faixa de atraso, sem os de devedor com marcador de
     * vulnerabilidade ativo. Maduro = formalizado há 60 dias ou mais em {@code asOf}.
     *
     * @param valueBand nula = todas
     */
    List<PolicySegment> agreements(UUID creditorId, LocalDate from, LocalDate to, String valueBand, Instant asOf);

    /** Consultas de condições e pedidos "nenhuma condição cabe" do portal no intervalo, por carteira × faixa. */
    List<PolicySegment> portal(UUID creditorId, Instant from, Instant to);
}
