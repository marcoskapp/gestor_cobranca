package br.com.gestorcobranca.legal.infrastructure.provider.manual;

import br.com.gestorcobranca.legal.application.port.out.RealEstateRegistryProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Procedimento no registro de imóveis conduzido fora da plataforma e lançado na medida. */
@Component
@ConditionalOnProperty(prefix = "app.legal.providers", name = "real-estate-registry",
        havingValue = ManualProviderSupport.CODE, matchIfMissing = true)
class ManualRealEstateRegistryProvider extends ManualProviderSupport implements RealEstateRegistryProvider {

    @Override
    public SubmissionReceipt startEnforcement(RealEstateEnforcementRequest request) {
        throw notAutomatic("real-estate-registry.start");
    }

    @Override
    public SubmissionStatus status(String reference) {
        throw notAutomatic("real-estate-registry.status");
    }
}
