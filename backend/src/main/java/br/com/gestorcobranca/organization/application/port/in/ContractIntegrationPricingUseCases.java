package br.com.gestorcobranca.organization.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Condições de cobrança das integrações (API pública) no contrato de prestação de serviço do credor: dia de
 * fechamento do ciclo de consumo e preços (mensalidade, franquias e excedente por chamada, MB recebido, MB enviado e
 * registro). Versionadas e não retroativas: cada alteração vale a partir do próximo ciclo de consumo (ciclos que
 * começam depois do registro). O módulo {@code publicapi} lê as condições aplicáveis por
 * {@link #applicable(UUID, Instant)} — o faturamento não mantém tabela de preços própria.
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
