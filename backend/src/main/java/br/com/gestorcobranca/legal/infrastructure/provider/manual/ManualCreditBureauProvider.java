package br.com.gestorcobranca.legal.infrastructure.provider.manual;

import br.com.gestorcobranca.legal.application.port.out.CreditBureauProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Negativação feita no sistema do birô; protocolo e situação lançados na medida. */
@Component
@ConditionalOnProperty(prefix = "app.legal.providers", name = "credit-bureau", havingValue = ManualProviderSupport.CODE,
        matchIfMissing = true)
class ManualCreditBureauProvider extends ManualProviderSupport implements CreditBureauProvider {

    @Override
    public SubmissionReceipt include(ListingRequest request) {
        throw notAutomatic("credit-bureau.include");
    }

    @Override
    public SubmissionStatus status(String reference) {
        throw notAutomatic("credit-bureau.status");
    }

    @Override
    public SubmissionReceipt remove(String reference) {
        throw notAutomatic("credit-bureau.remove");
    }
}
