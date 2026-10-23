package br.com.gestorcobranca.legal.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Registro eletrônico de imóveis (ONR/SAEC): procedimento extrajudicial de consolidação da
 * propriedade fiduciária (Lei 9.514/1997) ou de execução da hipoteca (Lei 14.711/2023) e seu
 * acompanhamento.
 */
public interface RealEstateRegistryProvider extends LegalProvider {

    SubmissionReceipt startEnforcement(RealEstateEnforcementRequest request);

    SubmissionStatus status(String reference);

    record RealEstateEnforcementRequest(UUID measureId, String collateralType, String registryNumber,
                                        String registryOffice, String municipality, String state, String debtorDocument,
                                        String debtorName, BigDecimal amount) {
    }
}
