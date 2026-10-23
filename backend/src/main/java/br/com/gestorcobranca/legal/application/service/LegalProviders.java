package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.out.CourtDataProvider;
import br.com.gestorcobranca.legal.application.port.out.CreditBureauProvider;
import br.com.gestorcobranca.legal.application.port.out.LegalProvider;
import br.com.gestorcobranca.legal.application.port.out.ProtestProvider;
import br.com.gestorcobranca.legal.application.port.out.PublicationsProvider;
import br.com.gestorcobranca.legal.application.port.out.RealEstateRegistryProvider;
import br.com.gestorcobranca.legal.application.port.out.VehicleLienProvider;
import br.com.gestorcobranca.legal.domain.model.MeasureType;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.springframework.stereotype.Component;

/**
 * Adaptadores ativos das portas de integração (um por porta, escolhido por
 * {@code app.legal.providers.*}). Com o adaptador manual a operação automática é recusada com
 * {@code legal.provider_manual} — o usuário lança o resultado pela API.
 */
@Component
class LegalProviders {

    static final String NO_PROVIDER = "manual";

    private final CourtDataProvider courtData;
    private final PublicationsProvider publications;
    private final ProtestProvider protest;
    private final CreditBureauProvider creditBureau;
    private final RealEstateRegistryProvider realEstate;
    private final VehicleLienProvider vehicleLien;
    private final LegalMetrics metrics;

    LegalProviders(CourtDataProvider courtData, PublicationsProvider publications, ProtestProvider protest,
                   CreditBureauProvider creditBureau, RealEstateRegistryProvider realEstate,
                   VehicleLienProvider vehicleLien, LegalMetrics metrics) {
        this.courtData = courtData;
        this.publications = publications;
        this.protest = protest;
        this.creditBureau = creditBureau;
        this.realEstate = realEstate;
        this.vehicleLien = vehicleLien;
        this.metrics = metrics;
    }

    CourtDataProvider courtData() {
        return courtData;
    }

    PublicationsProvider publications() {
        return publications;
    }

    ProtestProvider protest() {
        return protest;
    }

    CreditBureauProvider creditBureau() {
        return creditBureau;
    }

    RealEstateRegistryProvider realEstate() {
        return realEstate;
    }

    VehicleLienProvider vehicleLien() {
        return vehicleLien;
    }

    /**
     * Provedor da medida; nulo para a notificação (sem integração prevista).
     *
     * @param collateralType tipo da garantia (execução extrajudicial)
     */
    LegalProvider forMeasure(MeasureType type, String collateralType) {
        return switch (type) {
            case NOTICE -> null;
            case PROTEST -> protest;
            case CREDIT_BUREAU_LISTING -> creditBureau;
            case COLLATERAL_ENFORCEMENT -> "VEHICLE_FIDUCIARY_ALIENATION".equals(collateralType) ? vehicleLien : realEstate;
        };
    }

    String codeForMeasure(MeasureType type, String collateralType) {
        LegalProvider provider = forMeasure(type, collateralType);
        return provider == null ? NO_PROVIDER : provider.code();
    }

    /** Exige adaptador automático para a operação; caso contrário, conflito {@code legal.provider_manual}. */
    void requireAutomatic(LegalProvider provider, String port, String operation) {
        if (provider == null || !provider.automatic()) {
            metrics.manualProvider(port, operation);
            throw new ConflictException("legal.provider_manual",
                    "Integração ainda não habilitada para esta operação: lance o resultado manualmente");
        }
    }
}
