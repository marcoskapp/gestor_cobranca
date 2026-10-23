package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Garantias dos contratos de origem: cadastro com identificação estruturada do bem, registro do
 * gravame, vínculo opcional a títulos específicos, avaliações (histórico append-only; o valor
 * recuperável estimado é a avaliação mais recente) e ciclo de situação validado. Toda alteração
 * é auditada; cadastro, avaliação e mudança de situação geram eventos de negócio.
 * <p>
 * Escopo por credor do contrato: leitura fora do escopo → 404; comandos → 403. Também é a porta
 * pela qual o módulo legal atualiza a situação da garantia durante a execução.
 */
public interface CollateralUseCases {

    List<CollateralView> listByContract(UUID contractId);

    CollateralView get(UUID collateralId);

    /** Mesmo bem já cadastrado no contrato, ou mesmo identificador externo → conflito {@code collateral.duplicate}. */
    CollateralView register(UUID contractId, CollateralCommand command);

    /** Altera descrição, identificação do bem, gravame e títulos cobertos (o tipo é imutável). */
    CollateralView update(UUID collateralId, CollateralCommand command);

    /** @param status ACTIVE, UNDER_ENFORCEMENT, SEIZED, CONSOLIDATED, AUCTIONED, RELEASED ou WRITTEN_OFF */
    CollateralView changeStatus(UUID collateralId, String status, String reason);

    AppraisalView appraise(UUID collateralId, AppraiseCommand command);

    /** Avaliações da mais recente para a mais antiga. */
    List<AppraisalView> appraisals(UUID collateralId);

    /**
     * @param type       VEHICLE_FIDUCIARY_ALIENATION, REAL_ESTATE_FIDUCIARY_ALIENATION, MORTGAGE, PLEDGE,
     *                   RECEIVABLES_FIDUCIARY_ASSIGNMENT, SURETY_INSURANCE ou OTHER (ignorado na alteração)
     * @param externalId identificador da garantia no credor (opcional; ignorado na alteração)
     * @param titleIds   títulos do contrato cobertos; vazio = todos
     */
    record CollateralCommand(String type, String externalId, String description, AssetCommand asset,
                             RegistrationCommand registration, Set<UUID> titleIds) {
    }

    /**
     * Identificação do bem. Para VEHICLE informe ao menos placa, RENAVAM ou chassi; para
     * REAL_ESTATE, matrícula, cartório, município e UF; OTHER usa a descrição da garantia.
     *
     * @param kind VEHICLE, REAL_ESTATE ou OTHER
     */
    record AssetCommand(String kind, String plate, String renavam, String chassis, Integer manufactureYear,
                        Integer modelYear, String registryNumber, String registryOffice, String municipality,
                        String state) {
    }

    /** @param authority órgão ou cartório do gravame/registro */
    record RegistrationCommand(String authority, String number, LocalDate date) {
    }

    /** @param source CREDITOR, APPRAISER, FIPE, MARKET ou AUCTION */
    record AppraiseCommand(BigDecimal amount, LocalDate appraisalDate, String source, String notes) {
    }

    /** @param plate placa formatada (ABC-1234 ou ABC1D23) */
    record AssetView(String kind, String summary, String plate, String renavam, String chassis, Integer manufactureYear,
                     Integer modelYear, String registryNumber, String registryOffice, String municipality,
                     String state) {
    }

    record RegistrationView(String authority, String number, LocalDate date) {
    }

    /**
     * @param estimatedValue valor recuperável estimado (última avaliação); nulo sem avaliação
     * @param titleIds       títulos cobertos; vazio = todos os títulos do contrato
     */
    record CollateralView(UUID id, UUID contractId, UUID creditorId, String externalId, String type,
                          String description, AssetView asset, RegistrationView registration, String status,
                          String statusReason, Instant statusChangedAt, BigDecimal estimatedValue,
                          LocalDate lastAppraisalDate, Set<UUID> titleIds) {
    }

    record AppraisalView(UUID id, UUID collateralId, BigDecimal amount, LocalDate appraisalDate, String source,
                         String notes, UUID importBatchId, Instant recordedAt, UUID recordedBy) {
    }
}
