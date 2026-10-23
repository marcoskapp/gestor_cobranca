package br.com.gestorcobranca.debt.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** Modalidade da garantia, que restringe a natureza do bem admitida. */
public enum CollateralType {
    VEHICLE_FIDUCIARY_ALIENATION,
    REAL_ESTATE_FIDUCIARY_ALIENATION,
    MORTGAGE,
    /** Penhor: bens móveis em geral, inclusive veículos. */
    PLEDGE,
    RECEIVABLES_FIDUCIARY_ASSIGNMENT,
    SURETY_INSURANCE,
    OTHER;

    public Set<AssetKind> allowedAssetKinds() {
        return switch (this) {
            case VEHICLE_FIDUCIARY_ALIENATION -> EnumSet.of(AssetKind.VEHICLE);
            case REAL_ESTATE_FIDUCIARY_ALIENATION, MORTGAGE -> EnumSet.of(AssetKind.REAL_ESTATE);
            case PLEDGE -> EnumSet.of(AssetKind.VEHICLE, AssetKind.OTHER);
            case RECEIVABLES_FIDUCIARY_ASSIGNMENT, SURETY_INSURANCE -> EnumSet.of(AssetKind.OTHER);
            case OTHER -> EnumSet.allOf(AssetKind.class);
        };
    }
}
