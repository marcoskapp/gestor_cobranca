package br.com.gestorcobranca.legal.infrastructure.provider.manual;

import br.com.gestorcobranca.legal.application.port.out.PublicationsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Publicações lançadas manualmente como andamento {@code PUBLICATION}. */
@Component
@ConditionalOnProperty(prefix = "app.legal.providers", name = "publications", havingValue = ManualProviderSupport.CODE,
        matchIfMissing = true)
class ManualPublicationsProvider extends ManualProviderSupport implements PublicationsProvider {

    @Override
    public List<CapturedPublication> fetch(PublicationQuery query) {
        throw notAutomatic("publications.fetch");
    }
}
