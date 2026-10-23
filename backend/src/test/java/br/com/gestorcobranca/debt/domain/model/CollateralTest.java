package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollateralTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private static Collateral vehicle() {
        return new Collateral(UUID.randomUUID(), UUID.randomUUID(), null, CollateralType.VEHICLE_FIDUCIARY_ALIENATION,
                "Onix 1.0", AssetIdentification.vehicle("abc-1234", null, null, 2022, 2023));
    }

    @Test
    void vehicleIdentificationIsNormalizedAndValidated() {
        AssetIdentification a = AssetIdentification.vehicle(" abc1d23 ", "639354807", "9bwzzz377vt004251", 2020, 2021);
        assertThat(a.getVehiclePlate()).isEqualTo("ABC1D23");
        assertThat(a.getVehicleRenavam()).isEqualTo("00639354807");
        assertThat(a.getVehicleChassis()).isEqualTo("9BWZZZ377VT004251");
        assertThat(a.summary()).isEqualTo("veículo ABC1D23");
        assertThat(AssetIdentification.vehicle("ABC1234", null, null, null, null).summary()).isEqualTo("veículo ABC-1234");

        assertThatThrownBy(() -> AssetIdentification.vehicle(null, null, null, 2020, 2020))
                .hasFieldOrPropertyWithValue("code", "collateral.vehicle_identifier_required");
        assertThatThrownBy(() -> AssetIdentification.vehicle("AB12345", null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "collateral.plate_invalid");
        assertThatThrownBy(() -> AssetIdentification.vehicle(null, "12345678901", null, null, null))
                .hasFieldOrPropertyWithValue("code", "collateral.renavam_invalid");
        assertThatThrownBy(() -> AssetIdentification.vehicle(null, null, "9BWZZZ377VT00425I", null, null))
                .hasFieldOrPropertyWithValue("code", "collateral.chassis_invalid");
        assertThatThrownBy(() -> AssetIdentification.vehicle("ABC1234", null, null, 2020, 2022))
                .hasFieldOrPropertyWithValue("code", "collateral.vehicle_year_invalid");
    }

    @Test
    void realEstateRequiresRegistryOfficeMunicipalityAndState() {
        AssetIdentification a = AssetIdentification.realEstate("12.345", "2º Registro de Imóveis  de Campinas",
                "Campinas", "sp");
        assertThat(a.getRealEstateRegistryNumber()).isEqualTo("12345");
        assertThat(a.getRealEstateRegistryOffice()).isEqualTo("2º Registro de Imóveis de Campinas");
        assertThat(a.getRealEstateState()).isEqualTo("SP");
        assertThat(a.sameAs(AssetIdentification.realEstate("12345", "2º Registro de Imóveis de Campinas", "Campinas",
                "SP"))).isTrue();

        assertThatThrownBy(() -> AssetIdentification.realEstate("123", "Cartório", "Campinas", "XX"))
                .hasFieldOrPropertyWithValue("code", "collateral.state_invalid");
        assertThatThrownBy(() -> AssetIdentification.realEstate("123", null, "Campinas", "SP"))
                .hasFieldOrPropertyWithValue("code", "collateral.registry_office_required");
    }

    @Test
    void assetKindMustMatchCollateralTypeAndOtherAssetsNeedDescription() {
        assertThatThrownBy(() -> new Collateral(UUID.randomUUID(), UUID.randomUUID(), null, CollateralType.MORTGAGE, null,
                AssetIdentification.vehicle("ABC1234", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "collateral.asset_kind_mismatch");
        assertThatThrownBy(() -> new Collateral(UUID.randomUUID(), UUID.randomUUID(), "G-1",
                CollateralType.RECEIVABLES_FIDUCIARY_ASSIGNMENT, " ", AssetIdentification.other()))
                .hasFieldOrPropertyWithValue("code", "collateral.description_required");
        Collateral pledge = new Collateral(UUID.randomUUID(), UUID.randomUUID(), "G-2", CollateralType.PLEDGE,
                "Máquinas industriais", AssetIdentification.other());
        assertThat(pledge.getStatus()).isEqualTo(CollateralStatus.ACTIVE);
    }

    @Test
    void statusFollowsValidatedTransitions() {
        Collateral c = vehicle();
        assertThat(c.changeStatus(CollateralStatus.UNDER_ENFORCEMENT, "busca e apreensão ajuizada", NOW))
                .isEqualTo(CollateralStatus.ACTIVE);
        c.changeStatus(CollateralStatus.SEIZED, "veículo apreendido", NOW);
        c.changeStatus(CollateralStatus.AUCTIONED, "leilão realizado", NOW);
        assertThat(c.getStatus().isFinal()).isTrue();
        assertThat(c.getStatusReason()).isEqualTo("leilão realizado");

        assertThatThrownBy(() -> c.changeStatus(CollateralStatus.ACTIVE, "reabrir", NOW))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "collateral.invalid_transition");
        assertThatThrownBy(() -> vehicle().changeStatus(CollateralStatus.SEIZED, "pulo", NOW))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> vehicle().changeStatus(CollateralStatus.RELEASED, " ", NOW))
                .hasFieldOrPropertyWithValue("code", "collateral.reason_required");
        assertThat(CollateralStatus.CONSOLIDATED.canTransitionTo(CollateralStatus.RELEASED)).isFalse();
        Collateral underEnforcement = vehicle();
        underEnforcement.changeStatus(CollateralStatus.UNDER_ENFORCEMENT, "notificação", NOW);
        assertThatThrownBy(() -> underEnforcement.changeStatus(CollateralStatus.CONSOLIDATED, "consolidar", NOW))
                .hasFieldOrPropertyWithValue("code", "collateral.status_not_applicable");
        assertThat(CollateralStatus.UNDER_ENFORCEMENT.canTransitionTo(CollateralStatus.ACTIVE)).isTrue();
    }

    @Test
    void estimatedValueFollowsMostRecentAppraisal() {
        Collateral c = vehicle();
        CollateralAppraisal march = new CollateralAppraisal(c.getId(), Money.of("50000.00"), LocalDate.of(2026, 3, 1),
                AppraisalSource.FIPE, null, null);
        CollateralAppraisal january = new CollateralAppraisal(c.getId(), Money.of("55000.00"), LocalDate.of(2026, 1, 1),
                AppraisalSource.CREDITOR, null, null);

        assertThat(c.recordAppraisal(march)).isTrue();
        assertThat(c.recordAppraisal(january)).isFalse();
        assertThat(c.getEstimatedValue()).isEqualTo(Money.of("50000.00"));
        assertThat(c.getLastAppraisalDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(march.samePositionAs(Money.of("50000.00"), LocalDate.of(2026, 3, 1), AppraisalSource.FIPE)).isTrue();

        assertThatThrownBy(() -> new CollateralAppraisal(c.getId(), Money.of("-1.00"), LocalDate.of(2026, 3, 1),
                AppraisalSource.FIPE, null, null)).hasFieldOrPropertyWithValue("code", "collateral.appraisal_amount_invalid");
        CollateralAppraisal other = new CollateralAppraisal(UUID.randomUUID(), Money.of("1.00"), LocalDate.of(2026, 3, 1),
                AppraisalSource.FIPE, null, null);
        assertThatThrownBy(() -> c.recordAppraisal(other)).hasFieldOrPropertyWithValue("code", "collateral.appraisal_mismatch");
    }

    @Test
    void describeDetectsChangesAndTitleCoverage() {
        Collateral c = vehicle();
        UUID contract = c.getOriginContractId();
        UUID title = UUID.randomUUID();
        assertThat(c.covers(title, contract)).isTrue();
        assertThat(c.covers(title, UUID.randomUUID())).isFalse();

        assertThat(c.coverTitles(Set.of(title))).isTrue();
        assertThat(c.coverTitles(Set.of(title))).isFalse();
        assertThat(c.covers(UUID.randomUUID(), contract)).isFalse();

        assertThat(c.describe("Onix 1.0", AssetIdentification.vehicle("ABC1234", null, null, 2022, 2023))).isFalse();
        assertThat(c.describe("Onix 1.0 LT", AssetIdentification.vehicle("ABC1234", null, null, 2022, 2023))).isTrue();
        assertThat(c.registerLien("DETRAN-SP", "G123", LocalDate.of(2022, 5, 1))).isTrue();
        assertThatThrownBy(() -> c.registerLien(null, null, LocalDate.of(2022, 5, 1)))
                .hasFieldOrPropertyWithValue("code", "collateral.registration_incomplete");
    }

    @Test
    void obligationPartyCannotBeThePrincipalAndEndsOnce() {
        UUID principal = UUID.randomUUID();
        assertThatThrownBy(() -> new ObligationParty(UUID.randomUUID(), UUID.randomUUID(), principal, principal,
                ObligationRole.GUARANTOR_AVAL, null))
                .hasFieldOrPropertyWithValue("code", "obligation_party.principal_cannot_be_party");
        assertThatThrownBy(() -> new ObligationParty(UUID.randomUUID(), UUID.randomUUID(), principal, UUID.randomUUID(),
                ObligationRole.PRINCIPAL, null)).hasFieldOrPropertyWithValue("code", "obligation_party.principal_not_allowed");

        ObligationParty surety = new ObligationParty(UUID.randomUUID(), UUID.randomUUID(), principal, UUID.randomUUID(),
                ObligationRole.GUARANTOR_SURETY, LocalDate.of(2025, 1, 1));
        assertThatThrownBy(() -> surety.end(LocalDate.of(2024, 12, 31), "exoneração"))
                .hasFieldOrPropertyWithValue("code", "obligation_party.end_before_start");
        surety.end(LocalDate.of(2026, 1, 1), "exoneração da fiança");
        assertThat(surety.isActive()).isFalse();
        assertThatThrownBy(() -> surety.end(LocalDate.of(2026, 2, 1), "de novo"))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "obligation_party.ended");
    }
}
