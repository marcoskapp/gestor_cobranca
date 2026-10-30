package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases.Totals;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;

final class UsageTotalsMapper {

    private UsageTotalsMapper() {
    }

    static Totals view(UsageTotals t) {
        return new Totals(t.requests(), t.billableRequests(), t.throttled(), t.clientErrors(), t.serverErrors(),
                t.bytesIn(), t.bytesOut(), t.records());
    }
}
