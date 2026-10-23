package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <strong>Porta interna</strong> (sem endpoint web) para o módulo ingestion gravar devedores e
 * títulos de uma carga (F04). Projetada para ser chamada linha a linha, cada chamada na sua
 * própria transação: cada método é autocontido, idempotente e seguro para reprocessamento —
 * reimportar o mesmo arquivo não duplica devedores, contatos, títulos, lançamentos ou saldos.
 * <p>
 * Problemas que não impedem a gravação da linha (contato inválido, título bloqueado para
 * alteração) voltam em {@link UpsertResult#warnings()} como códigos estáveis; violações que
 * impedem a gravação lançam exceção de domínio com código estável.
 */
public interface DebtIngestionPort {

    /** Casa pelo documento; contatos novos do registro são acrescentados (origem IMPORT). */
    UpsertResult upsertDebtor(DebtorRecord record);

    /**
     * Casa por (credor, identificador externo). Na criação lança a abertura do principal e abre
     * a titularidade; na atualização altera vencimento/dados e corrige o principal por
     * lançamento de ajuste, exceto em títulos quitados, cancelados ou devolvidos (aviso
     * {@code title.locked_status}). O saldo informado é registrado para conciliação.
     * Valida que a carteira pertence ao credor e aceita cargas.
     */
    UpsertResult upsertTitle(TitleRecord record, UUID importBatchId);

    /** Publica {@code PortfolioLoaded} ao fim do lote, para a abertura/atualização de casos. */
    void completeImport(ImportSummary summary);

    // ------------------------------------------------------------------------------------------
    // Campos opcionais do layout (garantias, coobrigados, natureza do título). Todos exigem que o
    // contrato de origem já exista — gravado por upsertTitle na mesma linha/lote — e são
    // idempotentes: reprocessar a linha devolve UNCHANGED. Ordem sugerida por linha:
    // upsertDebtor → upsertTitle → classifyContract → upsertObligationParty → upsertCollateral →
    // recordCollateralAppraisal (com o id devolvido por upsertCollateral).
    // Contrato inexistente → ResourceNotFoundException ("origin_contract.not_found").
    // ------------------------------------------------------------------------------------------

    /**
     * Natureza do instrumento do contrato. Reclassificar recalcula a prescrição dos títulos do
     * contrato. Devolve o id do contrato.
     */
    UpsertResult classifyContract(ContractNatureRecord record);

    /**
     * Coobrigado do contrato. A pessoa passa pelo mesmo fluxo de {@link #upsertDebtor}
     * (consolidação pelo documento, nome atualizado, contatos acrescentados com avisos); a
     * participação é casada por (contrato, pessoa, papel). Devolve o id da participação; os
     * avisos dos contatos voltam em {@code warnings}.
     */
    UpsertResult upsertObligationParty(ObligationPartyRecord record);

    /**
     * Garantia do contrato, casada pelo identificador externo (se informado) ou pelo bem
     * (veículo: chassi, RENAVAM ou placa; imóvel: matrícula, cartório, município e UF). Outros
     * bens exigem identificador externo ({@code collateral.external_id_required}). Na
     * atualização altera descrição, identificação, gravame e títulos cobertos; tipo e situação
     * não mudam pela carga (aviso {@code collateral.type_mismatch} se o tipo diferir). Devolve o
     * id da garantia.
     */
    UpsertResult upsertCollateral(CollateralRecord record);

    /**
     * Avaliação da garantia: gravada uma vez por lote e só quando difere da última avaliação da
     * mesma fonte (valor e data). Devolve o id da garantia.
     */
    UpsertResult recordCollateralAppraisal(CollateralAppraisalRecord record, UUID importBatchId);

    enum Outcome { CREATED, UPDATED, UNCHANGED }

    /** @param warnings códigos de aviso (ex.: {@code contact.phone_invalid}, {@code title.locked_status}) */
    record UpsertResult(UUID id, Outcome outcome, List<String> warnings) {

        public UpsertResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    record DebtorRecord(String document, String name, LocalDate birthDate, List<ContactRecord> contacts) {

        public DebtorRecord {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
        }
    }

    /**
     * @param type  PHONE, EMAIL ou ADDRESS
     * @param label MOBILE, HOME, WORK ou OTHER (opcional)
     */
    record ContactRecord(String type, String value, String label, boolean whatsapp) {
    }

    /**
     * @param contract             contrato de origem (opcional)
     * @param reportedBalance      saldo informado pelo credor (opcional)
     * @param balanceReferenceDate data-base do saldo informado (obrigatória com o saldo)
     */
    record TitleRecord(UUID creditorId, UUID portfolioId, UUID debtorId, String externalId, ContractRecord contract,
                       Integer installmentNumber, LocalDate dueDate, BigDecimal principalAmount,
                       BigDecimal reportedBalance, LocalDate balanceReferenceDate) {
    }

    record ContractRecord(String externalId, String product, LocalDate signedAt, BigDecimal originalAmount) {
    }

    /**
     * @param contractExternalId identificador do contrato no credor
     * @param instrumentType     CONTRACT, CCB, PROMISSORY_NOTE, DUPLICATE, CHECK, DEBT_CONFESSION ou OTHER
     */
    record ContractNatureRecord(UUID creditorId, String contractExternalId, String instrumentType,
                                boolean executiveTitle) {
    }

    /** @param role GUARANTOR_AVAL, GUARANTOR_SURETY, CO_DEBTOR ou THIRD_PARTY_COLLATERAL_PROVIDER */
    record ObligationPartyRecord(UUID creditorId, String contractExternalId, DebtorRecord person, String role,
                                 LocalDate sinceDate) {
    }

    /**
     * @param type             VEHICLE_FIDUCIARY_ALIENATION, REAL_ESTATE_FIDUCIARY_ALIENATION, MORTGAGE, PLEDGE,
     *                         RECEIVABLES_FIDUCIARY_ASSIGNMENT, SURETY_INSURANCE ou OTHER
     * @param assetKind        VEHICLE, REAL_ESTATE ou OTHER
     * @param titleExternalIds identificadores externos dos títulos cobertos (vazio = todos do contrato)
     */
    record CollateralRecord(UUID creditorId, String contractExternalId, String externalId, String type,
                            String description, String assetKind, String plate, String renavam, String chassis,
                            Integer manufactureYear, Integer modelYear, String registryNumber, String registryOffice,
                            String municipality, String state, String registrationAuthority,
                            String registrationNumber, LocalDate registrationDate, List<String> titleExternalIds) {

        public CollateralRecord {
            titleExternalIds = titleExternalIds == null ? List.of() : List.copyOf(titleExternalIds);
        }
    }

    /** @param source CREDITOR, APPRAISER, FIPE, MARKET ou AUCTION (padrão CREDITOR) */
    record CollateralAppraisalRecord(UUID collateralId, BigDecimal amount, LocalDate appraisalDate, String source,
                                     String notes) {
    }

    record ImportSummary(UUID importBatchId, UUID creditorId, UUID portfolioId, int titlesCreated, int titlesUpdated,
                         int debtorsAffected) {
    }
}
