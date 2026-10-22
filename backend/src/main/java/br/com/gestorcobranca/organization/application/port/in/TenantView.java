package br.com.gestorcobranca.organization.application.port.in;

import java.time.LocalTime;
import java.util.UUID;

public record TenantView(
        UUID id,
        String slug,
        String legalName,
        String tradeName,
        String cnpj,
        String status,
        boolean platform,
        LocalTime contactWindowStart,
        LocalTime contactWindowEnd,
        boolean contactOnSaturday,
        boolean contactOnSunday) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
