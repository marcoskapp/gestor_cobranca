package br.com.gestorcobranca.legal.infrastructure.provider.manual;

import br.com.gestorcobranca.legal.application.port.out.VehicleLienProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Execução extrajudicial do veículo conduzida fora da plataforma e lançada na medida. */
@Component
@ConditionalOnProperty(prefix = "app.legal.providers", name = "vehicle-lien", havingValue = ManualProviderSupport.CODE,
        matchIfMissing = true)
class ManualVehicleLienProvider extends ManualProviderSupport implements VehicleLienProvider {

    @Override
    public SubmissionReceipt startEnforcement(VehicleEnforcementRequest request) {
        throw notAutomatic("vehicle-lien.start");
    }

    @Override
    public SubmissionStatus status(String reference) {
        throw notAutomatic("vehicle-lien.status");
    }

    @Override
    public SubmissionReceipt releaseLien(String reference) {
        throw notAutomatic("vehicle-lien.release");
    }
}
