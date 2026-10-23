package br.com.gestorcobranca.legal.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Gravames de veículos (SNG/DETRAN): execução extrajudicial da alienação fiduciária de veículo
 * (DL 911/1969, art. 8º-B, incluído pela Lei 14.711/2023), acompanhamento e baixa do gravame.
 */
public interface VehicleLienProvider extends LegalProvider {

    SubmissionReceipt startEnforcement(VehicleEnforcementRequest request);

    SubmissionStatus status(String reference);

    SubmissionReceipt releaseLien(String reference);

    record VehicleEnforcementRequest(UUID measureId, String plate, String renavam, String chassis,
                                     String debtorDocument, String debtorName, BigDecimal amount) {
    }
}
