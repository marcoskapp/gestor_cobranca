package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.AssetIdentification;
import br.com.gestorcobranca.debt.domain.model.Collateral;
import br.com.gestorcobranca.debt.domain.repository.CollateralRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CollateralJpaRepository extends JpaRepository<Collateral, UUID>, CollateralRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Collateral c where c.id = :id")
    Optional<Collateral> findByIdForUpdate(UUID id);

    @Override
    List<Collateral> findByOriginContractIdOrderByCreatedAtAsc(UUID originContractId);

    @Override
    List<Collateral> findByOriginContractIdInOrderByCreatedAtAsc(Collection<UUID> originContractIds);

    @Override
    Optional<Collateral> findByOriginContractIdAndExternalId(UUID originContractId, String externalId);

    @Override
    default Optional<Collateral> findSameAsset(UUID originContractId, AssetIdentification asset) {
        return switch (asset.getKind()) {
            case VEHICLE -> firstVehicle(originContractId, asset.getVehicleChassis(), asset.getVehicleRenavam(),
                    asset.getVehiclePlate()).stream().findFirst();
            case REAL_ESTATE -> findRealEstate(originContractId, asset.getRealEstateRegistryNumber(),
                    asset.getRealEstateRegistryOffice(), asset.getRealEstateMunicipality(), asset.getRealEstateState());
            case OTHER -> Optional.empty();
        };
    }

    /** Qualquer identificador de veículo coincidente; o chassi tem precedência. */
    @Query("""
            select c from Collateral c where c.originContractId = :contractId and c.asset.kind = br.com.gestorcobranca.debt.domain.model.AssetKind.VEHICLE
            and (c.asset.vehicleChassis = :chassis or c.asset.vehicleRenavam = :renavam or c.asset.vehiclePlate = :plate)
            order by case when c.asset.vehicleChassis = :chassis then 0 when c.asset.vehicleRenavam = :renavam then 1 else 2 end
            """)
    List<Collateral> firstVehicle(UUID contractId, String chassis, String renavam, String plate);

    @Query("""
            select c from Collateral c where c.originContractId = :contractId and c.asset.kind = br.com.gestorcobranca.debt.domain.model.AssetKind.REAL_ESTATE
            and c.asset.realEstateRegistryNumber = :number and c.asset.realEstateState = :state
            and lower(c.asset.realEstateRegistryOffice) = lower(:office)
            and lower(c.asset.realEstateMunicipality) = lower(:municipality)
            """)
    Optional<Collateral> findRealEstate(UUID contractId, String number, String office, String municipality, String state);
}
