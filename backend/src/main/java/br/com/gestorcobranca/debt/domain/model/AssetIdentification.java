package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Identificação estruturada do bem dado em garantia, conforme a natureza:
 * <ul>
 *   <li>veículo: placa (antiga ou Mercosul), RENAVAM (com dígito verificador) e chassi (VIN) —
 *       ao menos um deles —, além de ano de fabricação e ano do modelo;</li>
 *   <li>imóvel: matrícula, cartório de registro de imóveis, município e UF (todos obrigatórios);</li>
 *   <li>outros bens: sem campos próprios; a descrição da garantia identifica o bem.</li>
 * </ul>
 * Dados que identificam pessoa física (nome, CPF do proprietário) não fazem parte da
 * identificação: o proprietário é uma parte da obrigação ({@link ObligationParty}).
 * Imutável: uma correção substitui a identificação inteira.
 */
@Embeddable
public class AssetIdentification {

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2100;
    private static final Set<String> STATES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT",
            "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_kind", nullable = false, length = 20)
    private AssetKind kind;

    @Column(name = "vehicle_plate", length = 7)
    private String vehiclePlate;

    @Column(name = "vehicle_renavam", length = 11)
    private String vehicleRenavam;

    @Column(name = "vehicle_chassis", length = 17)
    private String vehicleChassis;

    @Column(name = "vehicle_manufacture_year")
    private Integer vehicleManufactureYear;

    @Column(name = "vehicle_model_year")
    private Integer vehicleModelYear;

    @Column(name = "real_estate_registry_number", length = 30)
    private String realEstateRegistryNumber;

    @Column(name = "real_estate_registry_office", length = 150)
    private String realEstateRegistryOffice;

    @Column(name = "real_estate_municipality", length = 100)
    private String realEstateMunicipality;

    @Column(name = "real_estate_state", length = 2)
    private String realEstateState;

    protected AssetIdentification() {
    }

    private AssetIdentification(AssetKind kind) {
        this.kind = kind;
    }

    /**
     * @param plate    placa (opcional), com ou sem hífen
     * @param renavam  RENAVAM (opcional), 9 a 11 dígitos
     * @param chassis  chassi (opcional), 17 caracteres
     */
    public static AssetIdentification vehicle(String plate, String renavam, String chassis, Integer manufactureYear,
                                              Integer modelYear) {
        AssetIdentification id = new AssetIdentification(AssetKind.VEHICLE);
        id.vehiclePlate = blank(plate) ? null : VehiclePlate.parse(plate).value();
        id.vehicleRenavam = blank(renavam) ? null : Renavam.parse(renavam).value();
        id.vehicleChassis = blank(chassis) ? null : Vin.parse(chassis).value();
        Guard.require(id.vehiclePlate != null || id.vehicleRenavam != null || id.vehicleChassis != null,
                "collateral.vehicle_identifier_required", "Informe placa, RENAVAM ou chassi do veículo");
        Guard.require(manufactureYear == null || manufactureYear >= MIN_YEAR && manufactureYear <= MAX_YEAR,
                "collateral.vehicle_year_invalid", "Ano de fabricação inválido");
        Guard.require(modelYear == null || modelYear >= MIN_YEAR && modelYear <= MAX_YEAR,
                "collateral.vehicle_year_invalid", "Ano do modelo inválido");
        // o ano do modelo é o de fabricação ou o seguinte
        Guard.require(manufactureYear == null || modelYear == null
                        || modelYear >= manufactureYear && modelYear <= manufactureYear + 1,
                "collateral.vehicle_year_invalid", "Ano do modelo deve ser o de fabricação ou o seguinte");
        id.vehicleManufactureYear = manufactureYear;
        id.vehicleModelYear = modelYear;
        return id;
    }

    /**
     * @param registryNumber número da matrícula
     * @param registryOffice cartório de registro de imóveis (ex.: "2º Registro de Imóveis de Campinas")
     * @param state          UF (sigla)
     */
    public static AssetIdentification realEstate(String registryNumber, String registryOffice, String municipality,
                                                 String state) {
        AssetIdentification id = new AssetIdentification(AssetKind.REAL_ESTATE);
        Guard.require(!blank(registryNumber), "collateral.registry_number_required", "Matrícula do imóvel obrigatória");
        String number = registryNumber.replaceAll("[\\s.]", "").toUpperCase(Locale.ROOT);
        Guard.require(number.matches("[0-9A-Z/-]{1,30}"), "collateral.registry_number_invalid",
                "Matrícula deve ter até 30 caracteres (números, letras, / ou -)");
        id.realEstateRegistryNumber = number;
        id.realEstateRegistryOffice = requiredText(registryOffice, 150, "collateral.registry_office_required",
                "Cartório de registro de imóveis");
        id.realEstateMunicipality = requiredText(municipality, 100, "collateral.municipality_required", "Município do imóvel");
        String uf = state == null ? null : state.trim().toUpperCase(Locale.ROOT);
        Guard.require(uf != null && STATES.contains(uf), "collateral.state_invalid", "UF do imóvel inválida");
        id.realEstateState = uf;
        return id;
    }

    /** Bem sem identificação estruturada: a descrição da garantia é obrigatória. */
    public static AssetIdentification other() {
        return new AssetIdentification(AssetKind.OTHER);
    }

    private static String requiredText(String value, int max, String code, String label) {
        Guard.require(!blank(value), code, label + " obrigatório");
        String normalized = value.trim().replaceAll("\\s+", " ");
        Guard.require(normalized.length() <= max, code, "%s deve ter até %d caracteres".formatted(label, max));
        return normalized;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Mesma identificação (usado para detectar alteração). */
    public boolean sameAs(AssetIdentification other) {
        return other != null && kind == other.kind && Objects.equals(vehiclePlate, other.vehiclePlate)
                && Objects.equals(vehicleRenavam, other.vehicleRenavam) && Objects.equals(vehicleChassis, other.vehicleChassis)
                && Objects.equals(vehicleManufactureYear, other.vehicleManufactureYear)
                && Objects.equals(vehicleModelYear, other.vehicleModelYear)
                && Objects.equals(realEstateRegistryNumber, other.realEstateRegistryNumber)
                && Objects.equals(realEstateRegistryOffice, other.realEstateRegistryOffice)
                && Objects.equals(realEstateMunicipality, other.realEstateMunicipality)
                && Objects.equals(realEstateState, other.realEstateState);
    }

    /** Resumo legível para auditoria e listagens (não contém dados pessoais). */
    public String summary() {
        return switch (kind) {
            case VEHICLE -> "veículo " + firstNonNull(plate().map(VehiclePlate::formatted).orElse(null), vehicleChassis,
                    vehicleRenavam);
            case REAL_ESTATE -> "matrícula %s — %s, %s/%s".formatted(realEstateRegistryNumber, realEstateRegistryOffice,
                    realEstateMunicipality, realEstateState);
            case OTHER -> "outro bem";
        };
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return "";
    }

    public java.util.Optional<VehiclePlate> plate() {
        return vehiclePlate == null ? java.util.Optional.empty() : java.util.Optional.of(new VehiclePlate(vehiclePlate));
    }

    public AssetKind getKind() {
        return kind;
    }

    public String getVehiclePlate() {
        return vehiclePlate;
    }

    public String getVehicleRenavam() {
        return vehicleRenavam;
    }

    public String getVehicleChassis() {
        return vehicleChassis;
    }

    public Integer getVehicleManufactureYear() {
        return vehicleManufactureYear;
    }

    public Integer getVehicleModelYear() {
        return vehicleModelYear;
    }

    public String getRealEstateRegistryNumber() {
        return realEstateRegistryNumber;
    }

    public String getRealEstateRegistryOffice() {
        return realEstateRegistryOffice;
    }

    public String getRealEstateMunicipality() {
        return realEstateMunicipality;
    }

    public String getRealEstateState() {
        return realEstateState;
    }
}
