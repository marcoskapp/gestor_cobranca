package br.com.gestorcobranca.legal.infrastructure.provider.manual;

import br.com.gestorcobranca.legal.application.port.out.CourtDataProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Andamentos lançados manualmente ({@code POST /api/v1/legal/lawsuits/{id}/movements}). */
@Component
@ConditionalOnProperty(prefix = "app.legal.providers", name = "court-data", havingValue = ManualProviderSupport.CODE,
        matchIfMissing = true)
class ManualCourtDataProvider extends ManualProviderSupport implements CourtDataProvider {

    @Override
    public CourtCapture fetch(CourtQuery query) {
        throw notAutomatic("court-data.fetch");
    }
}
