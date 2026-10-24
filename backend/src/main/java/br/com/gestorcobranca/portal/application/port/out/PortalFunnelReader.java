package br.com.gestorcobranca.portal.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Contagens agregadas do funil por portal e período (consulta de leitura). */
public interface PortalFunnelReader {

    Counts counts(UUID configurationId, Instant from, Instant until);

    record Counts(long accesses, long simulations, long acceptances, long pixIssued, long paid, long serviceRequests) {
    }
}
