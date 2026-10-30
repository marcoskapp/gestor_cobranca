package br.com.gestorcobranca.organization.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Condições de cobrança das integrações (API pública) no contrato de prestação de serviço do credor: dia de
 * fechamento do ciclo de consumo e preços (mensalidade, franquias e excedente por chamada, MB recebido, MB enviado e
 * registro). Versionadas: o <b>primeiro</b> cadastro de um contrato vale já no ciclo em andamento; cada
 * <b>alteração</b> posterior vale a partir do próximo ciclo de consumo (ciclos que começam depois do registro). O
 * módulo {@code publicapi} lê as condições aplicáveis por {@link #applicableToCycle(UUID, Instant, Instant)} — o
 * faturamento não mantém tabela de preços própria.
 */
public interface ContractIntegrationPricingUseCases {

    /** Dia de fechamento usado quando o credor não tem condições registradas: último dia do mês. */
    int DEFAULT_USAGE_CLOSING_DAY = 31;

    /** Registra nova versão das condições do contrato (vigente). Exige {@code CONTRACT_MANAGE}. */
    IntegrationPricingView define(UUID contractId, IntegrationPricingCommand command);

    /** Versões do contrato, da mais recente para a mais antiga. */
    List<IntegrationPricingView> history(UUID contractId);

    /**
     * Condições que valem para um ciclo de consumo do credor que começa em {@code cycleStart}: a versão mais recente
     * registrada antes desse instante, no contrato do credor vigente na data (no fuso de negócio) do início do ciclo
     * (havendo mais de um, o de início mais recente). Vazio quando não há contrato vigente ou condições registradas.
     */
    Optional<IntegrationPricingView> applicable(UUID creditorId, Instant cycleStart);

    /**
     * Condições que valem para o ciclo de consumo {@code [cycleStart, cycleEnd)} do credor: a versão de
     * {@link #applicable(UUID, Instant)} (registrada antes do início do ciclo); sem nenhuma, a <b>primeira</b> versão
     * do contrato, se registrada antes do fim do ciclo — o primeiro cadastro vale já no ciclo em andamento (decisão do
     * responsável, 608/624); as alterações seguintes valem só dos ciclos que começam depois do seu registro.
     */
    Optional<IntegrationPricingView> applicableToCycle(UUID creditorId, Instant cycleStart, Instant cycleEnd);

    /**
     * Contratos do credor que têm condições de cobrança das integrações registradas, com a vigência e a primeira
     * versão (registro e dia de fechamento): base do calendário dos ciclos de consumo, que começa no início de
     * vigência do contrato (decisão 624). Ordenados pelo início de vigência.
     */
    List<IntegrationContractView> integrationContracts(UUID creditorId);

    /**
     * Credores visíveis com condições de cobrança das integrações registradas em algum contrato: candidatos ao
     * fechamento automático dos ciclos, mesmo sem nenhum uso da API (decisão 624).
     */
    List<UUID> creditorsWithIntegrationPricing();

    /**
     * Contrato com condições de cobrança das integrações.
     *
     * @param endDate              fim da vigência (inclusivo) ou {@code null} se indeterminado
     * @param firstRegisteredAt    registro da primeira versão das condições: o fechamento automático não alcança
     *                             ciclos encerrados antes dessa data (sem retroatividade, decisão 624)
     * @param firstUsageClosingDay dia de fechamento da primeira versão: calendário dos ciclos do contrato antes de
     *                             haver versão aplicável
     */
    record IntegrationContractView(UUID contractId, String contractNumber, LocalDate startDate, LocalDate endDate,
                                   Instant firstRegisteredAt, int firstUsageClosingDay) {
    }

    /**
     * @param usageClosingDay dia do mês (1–31) em que fecha o ciclo de consumo das integrações; meses sem esse dia
     *                        fecham no último dia
     * @param reason          motivo da alteração (opcional, registrado na auditoria)
     */
    record IntegrationPricingCommand(int usageClosingDay, BigDecimal monthlyFee, long includedRequests,
                                     BigDecimal pricePerRequest, long includedMbIn, BigDecimal pricePerMbIn,
                                     long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                                     BigDecimal pricePerRecord, String reason) {
    }

    /** Versão registrada (imutável). */
    record IntegrationPricingView(UUID id, UUID contractId, String contractNumber, UUID creditorId, int version,
                                  int usageClosingDay, BigDecimal monthlyFee, long includedRequests,
                                  BigDecimal pricePerRequest, long includedMbIn, BigDecimal pricePerMbIn,
                                  long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                                  BigDecimal pricePerRecord, Instant registeredAt, UUID registeredBy, String reason) {
    }
}
