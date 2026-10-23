package br.com.gestorcobranca.legal.infrastructure.provider.manual;

import br.com.gestorcobranca.legal.application.port.out.ProtestProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Protesto apresentado fora da plataforma; protocolo e situação lançados na medida. */
@Component
@ConditionalOnProperty(prefix = "app.legal.providers", name = "protest", havingValue = ManualProviderSupport.CODE,
        matchIfMissing = true)
class ManualProtestProvider extends ManualProviderSupport implements ProtestProvider {

    @Override
    public SubmissionReceipt submit(ProtestRequest request) {
        throw notAutomatic("protest.submit");
    }

    @Override
    public SubmissionStatus status(String reference) {
        throw notAutomatic("protest.status");
    }

    @Override
    public SubmissionReceipt requestCancellation(String reference) {
        throw notAutomatic("protest.cancel");
    }
}
