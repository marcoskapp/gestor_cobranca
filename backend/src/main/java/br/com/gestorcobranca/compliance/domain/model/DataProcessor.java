package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Operador ou suboperador que recebe dados de devedores (registro das operações de tratamento, LGPD art. 37 e 39):
 * chave do provedor (a mesma das fontes de compartilhamento, ex.: {@code communication.whatsapp_cloud}), papel,
 * finalidade, categorias de dados, país e — fora do Brasil — a garantia da transferência internacional (art. 33),
 * referência do contrato/DPA e vigência. A chave não muda: outro provedor é outro cadastro.
 */
@Entity
@Table(name = "data_processor", schema = "compliance")
public class DataProcessor extends TenantScopedEntity {

    public static final String BRAZIL = "BR";
    /**
     * Prefixo da chave do operador cadastrado à mão (ex.: {@code custom.hospedagem}): não vem de nenhuma fonte de
     * compartilhamento, então entra na resposta ao titular pelo próprio cadastro (ativo e vigente).
     */
    public static final String CUSTOM_KEY_PREFIX = "custom.";
    private static final Pattern KEY = Pattern.compile("[a-z]+\\.[a-z_]+(:[0-9a-f-]{36})?");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    @Column(name = "provider_key", nullable = false, length = 80, updatable = false)
    private String providerKey;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ProcessorRole role;

    @Column(name = "purpose", nullable = false, length = 300)
    private String purpose;

    @Column(name = "data_categories", nullable = false, length = 300)
    private String dataCategories;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_safeguard", length = 40)
    private TransferSafeguard transferSafeguard;

    @Column(name = "contract_reference", length = 120)
    private String contractReference;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected DataProcessor() {
    }

    public DataProcessor(String providerKey, Details details) {
        Guard.require(providerKey != null && KEY.matcher(providerKey).matches(), "data_processor.key_invalid",
                "Chave do provedor inválida");
        this.providerKey = providerKey;
        apply(details);
    }

    /** Dados editáveis do cadastro. */
    public record Details(String name, ProcessorRole role, String purpose, String dataCategories, String countryCode,
                          TransferSafeguard transferSafeguard, String contractReference, LocalDate validFrom,
                          LocalDate validUntil, boolean active) {
    }

    public void update(Details details) {
        apply(details);
    }

    private void apply(Details d) {
        Guard.require(d.role() != null, "data_processor.role_required", "Papel obrigatório");
        Guard.require(d.validFrom() != null, "data_processor.valid_from_required", "Início da vigência obrigatório");
        Guard.require(d.validUntil() == null || !d.validUntil().isBefore(d.validFrom()), "data_processor.period_invalid",
                "Fim da vigência não pode ser anterior ao início");
        String country = d.countryCode() == null ? null : d.countryCode().trim().toUpperCase(Locale.ROOT);
        Guard.require(country != null && COUNTRY.matcher(country).matches(), "data_processor.country_invalid",
                "País inválido");
        boolean international = !BRAZIL.equals(country);
        Guard.require(!international || d.transferSafeguard() != null, "data_processor.safeguard_required",
                "Informe a garantia da transferência internacional (LGPD art. 33)");
        this.name = text(d.name(), 150, "data_processor.name_required", true);
        this.role = d.role();
        this.purpose = text(d.purpose(), 300, "data_processor.purpose_required", true);
        this.dataCategories = text(d.dataCategories(), 300, "data_processor.categories_required", true);
        this.countryCode = country;
        this.transferSafeguard = international ? d.transferSafeguard() : null;
        this.contractReference = text(d.contractReference(), 120, "data_processor.contract_reference_invalid", false);
        this.validFrom = d.validFrom();
        this.validUntil = d.validUntil();
        this.active = d.active();
    }

    private static String text(String value, int max, String code, boolean required) {
        if (value == null || value.isBlank()) {
            Guard.require(!required, code, "Campo obrigatório");
            return null;
        }
        String trimmed = value.trim();
        Guard.require(trimmed.length() <= max, code, "Campo deve ter até %d caracteres".formatted(max));
        return trimmed;
    }

    /** Ativo e com contrato vigente na data. */
    public boolean isCurrentOn(LocalDate date) {
        return active && !date.isBefore(validFrom) && (validUntil == null || !date.isAfter(validUntil));
    }

    public boolean isInternationalTransfer() {
        return !BRAZIL.equals(countryCode);
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getName() {
        return name;
    }

    public ProcessorRole getRole() {
        return role;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getDataCategories() {
        return dataCategories;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public TransferSafeguard getTransferSafeguard() {
        return transferSafeguard;
    }

    public String getContractReference() {
        return contractReference;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public boolean isActive() {
        return active;
    }
}
