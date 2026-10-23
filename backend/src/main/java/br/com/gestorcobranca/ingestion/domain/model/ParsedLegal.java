package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Dados jurídicos do contrato de origem informados na linha (A.4 de {@code docs/tecnico/desenho/juridico.md}):
 * natureza do título, coobrigados e garantia com avaliação. Os códigos já estão no vocabulário do
 * {@code DebtIngestionPort} ({@link LegalTerms}). Só existe quando a linha informa o contrato.
 *
 * @param nature     nula se a natureza não foi informada
 * @param collateral nula se nenhuma garantia foi informada
 */
public record ParsedLegal(Nature nature, List<Party> parties, Collateral collateral) {

    public ParsedLegal {
        parties = parties == null ? List.of() : List.copyOf(parties);
    }

    public boolean isEmpty() {
        return nature == null && parties.isEmpty() && collateral == null;
    }

    /** @param instrumentType CONTRACT, CCB, PROMISSORY_NOTE, DUPLICATE, CHECK, DEBT_CONFESSION ou OTHER */
    public record Nature(String instrumentType, boolean executiveTitle) {
    }

    /** @param role GUARANTOR_AVAL, GUARANTOR_SURETY, CO_DEBTOR ou THIRD_PARTY_COLLATERAL_PROVIDER */
    public record Party(Document document, String name, String role, LocalDate since) {
    }

    /**
     * @param type      tipo da garantia (código do debt)
     * @param assetKind VEHICLE, REAL_ESTATE ou OTHER
     * @param appraisal nula se não houver avaliação na linha
     */
    public record Collateral(String externalId, String type, String assetKind, String description, String plate,
                             String renavam, String chassis, Integer manufactureYear, Integer modelYear,
                             String registryNumber, String registryOffice, String municipality, String state,
                             String lienAuthority, String lienNumber, LocalDate lienDate, Appraisal appraisal) {
    }

    /** @param source CREDITOR, APPRAISER, FIPE, MARKET ou AUCTION */
    public record Appraisal(BigDecimal amount, LocalDate date, String source) {
    }
}
