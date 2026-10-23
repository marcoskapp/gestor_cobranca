package br.com.gestorcobranca.ingestion.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Vocabulário das colunas jurídicas do layout (natureza do título, papel do coobrigado, tipo de
 * garantia, bem e fonte da avaliação): termos em português usados pelos credores, convertidos nos
 * códigos do {@code DebtIngestionPort}. Os próprios códigos também são aceitos. Comparação sem
 * acentos e sem diferenciar maiúsculas; espaços e hífens equivalem a sublinhado.
 */
public final class LegalTerms {

    public static final Map<String, String> INSTRUMENT_TYPES = Map.ofEntries(
            Map.entry("CONTRATO", "CONTRACT"), Map.entry("CCB", "CCB"), Map.entry("CEDULA_DE_CREDITO_BANCARIO", "CCB"),
            Map.entry("CEDULA_CREDITO_BANCARIO", "CCB"), Map.entry("NOTA_PROMISSORIA", "PROMISSORY_NOTE"),
            Map.entry("DUPLICATA", "DUPLICATE"), Map.entry("CHEQUE", "CHECK"),
            Map.entry("CONFISSAO_DE_DIVIDA", "DEBT_CONFESSION"), Map.entry("CONFISSAO_DIVIDA", "DEBT_CONFESSION"),
            Map.entry("OUTRO", "OTHER"), Map.entry("OUTRA", "OTHER"));

    public static final Map<String, String> ROLES = Map.ofEntries(
            Map.entry("AVALISTA", "GUARANTOR_AVAL"), Map.entry("FIADOR", "GUARANTOR_SURETY"),
            Map.entry("CODEVEDOR", "CO_DEBTOR"), Map.entry("DEVEDOR_SOLIDARIO", "CO_DEBTOR"),
            Map.entry("GARANTIDOR", "THIRD_PARTY_COLLATERAL_PROVIDER"),
            Map.entry("TERCEIRO_GARANTIDOR", "THIRD_PARTY_COLLATERAL_PROVIDER"));

    public static final Map<String, String> COLLATERAL_TYPES = Map.ofEntries(
            Map.entry("ALIENACAO_FIDUCIARIA_VEICULO", "VEHICLE_FIDUCIARY_ALIENATION"),
            Map.entry("ALIENACAO_FIDUCIARIA_IMOVEL", "REAL_ESTATE_FIDUCIARY_ALIENATION"),
            Map.entry("HIPOTECA", "MORTGAGE"), Map.entry("PENHOR", "PLEDGE"),
            Map.entry("CESSAO_FIDUCIARIA_RECEBIVEIS", "RECEIVABLES_FIDUCIARY_ASSIGNMENT"),
            Map.entry("CESSAO_RECEBIVEIS", "RECEIVABLES_FIDUCIARY_ASSIGNMENT"),
            Map.entry("SEGURO_GARANTIA", "SURETY_INSURANCE"), Map.entry("OUTRA", "OTHER"), Map.entry("OUTRO", "OTHER"));

    public static final Map<String, String> ASSET_KINDS = Map.of("VEICULO", "VEHICLE", "IMOVEL", "REAL_ESTATE",
            "OUTRO", "OTHER");

    public static final Map<String, String> APPRAISAL_SOURCES = Map.of("CREDOR", "CREDITOR", "AVALIADOR", "APPRAISER",
            "FIPE", "FIPE", "MERCADO", "MARKET", "LEILAO", "AUCTION");

    private LegalTerms() {
    }

    /** Converte o termo no código; aceita também o próprio código. */
    public static Optional<String> parse(Map<String, String> vocabulary, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (vocabulary.containsKey(key)) {
            return Optional.of(vocabulary.get(key));
        }
        return vocabulary.containsValue(key) ? Optional.of(key) : Optional.empty();
    }

    /** Bem padrão do tipo de garantia quando {@code garantia_bem} não é informado. */
    public static String defaultAssetKind(String collateralType) {
        return switch (collateralType) {
            case "VEHICLE_FIDUCIARY_ALIENATION" -> "VEHICLE";
            case "REAL_ESTATE_FIDUCIARY_ALIENATION", "MORTGAGE" -> "REAL_ESTATE";
            default -> "OTHER";
        };
    }
}
