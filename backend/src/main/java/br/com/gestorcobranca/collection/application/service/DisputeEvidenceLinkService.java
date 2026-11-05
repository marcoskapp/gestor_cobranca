package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.in.DisputeEvidenceLinkUseCases;
import br.com.gestorcobranca.collection.application.port.out.DisputeLinkRoutes;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Entrega das evidências pelo link do pedido ao credor. A rota (tabela global) leva à assessoria; o pedido e as
 * evidências são lidos no tenant, sob RLS. Cada download válido conta no limite do link.
 */
@Service
class DisputeEvidenceLinkService implements DisputeEvidenceLinkUseCases {

    private final DisputeLinkRoutes routes;
    private final DisputeCreditorRequestRepository requests;
    private final DisputeEvidenceRepository evidences;
    private final FileStorage fileStorage;
    private final TenantExecution tenantExecution;
    private final AccessLogger accessLogger;
    private final CollectionMetrics metrics;
    private final CollectionProperties properties;
    private final Clock clock;

    DisputeEvidenceLinkService(DisputeLinkRoutes routes, DisputeCreditorRequestRepository requests,
                               DisputeEvidenceRepository evidences, FileStorage fileStorage,
                               TenantExecution tenantExecution, AccessLogger accessLogger, CollectionMetrics metrics,
                               CollectionProperties properties, Clock clock) {
        this.routes = routes;
        this.requests = requests;
        this.evidences = evidences;
        this.fileStorage = fileStorage;
        this.tenantExecution = tenantExecution;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Package open(String token, String clientIp) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{40,64}")) {
            reject(null, "malformed", clientIp);
        }
        DisputeLinkRoutes.Route route = routes.find(DisputeCreditorChannel.sha256(token)).orElse(null);
        if (route == null) {
            reject(null, "unknown", clientIp);
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(route.expiresAt())) {
            reject(route.tenantId(), "expired", clientIp);
        }
        String outcome = tenantExecution.callInTenant(route.tenantId(), "collection.dispute-link", () -> {
            DisputeCreditorRequest request = requests.findById(route.requestId()).orElse(null);
            if (request == null || !request.linkUsableAt(now)) {
                return "expired";
            }
            if (request.getLinkDownloads() >= properties.disputes().linkMaxDownloads()) {
                return "exhausted";
            }
            request.countDownload();
            requests.save(request);
            accessLogger.log(AccessEvent.anonymous(AccessEventType.FILE_DOWNLOADED, route.tenantId(), null,
                    Map.of("resource", "dispute_evidence_link", "resourceId", request.getId().toString(),
                            "disputeId", request.getDisputeId().toString(), "files",
                            Integer.toString(request.getEvidenceIds().size()), "ip", String.valueOf(clientIp))));
            return "served";
        });
        if (!"served".equals(outcome)) {
            reject(route.tenantId(), outcome, clientIp);
        }
        metrics.disputeLinkDownload("served");
        return new Package(route.tenantId(), route.requestId(), "contestacao-documentos.zip");
    }

    @Override
    public void write(Package pack, OutputStream out) {
        tenantExecution.inTenant(pack.tenantId(), "collection.dispute-link", () -> {
            DisputeCreditorRequest request = requests.findById(pack.requestId()).orElseThrow();
            List<DisputeEvidence> files = evidences.findByDisputeIdOrderByCreatedAtAsc(request.getDisputeId()).stream()
                    .filter(e -> request.getEvidenceIds().contains(e.getId())).toList();
            try {
                ZipOutputStream zip = new ZipOutputStream(out);
                Set<String> names = new HashSet<>();
                int n = 0;
                for (DisputeEvidence e : files) {
                    n++;
                    String name = uniqueName(names, n, e.getFileName());
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream in = fileStorage.open(e.getFileKey())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
                zip.finish();
                zip.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao entregar as evidências do link", e);
            }
        });
    }

    @Override
    public void rateLimited(String clientIp) {
        metrics.disputeLinkDownload("rate_limited");
        accessLogger.log(AccessEvent.anonymous(AccessEventType.DISPUTE_LINK_REJECTED, null, null,
                Map.of("reason", "rate_limited", "ip", String.valueOf(clientIp))));
    }

    private void reject(UUID tenantId, String reason, String clientIp) {
        metrics.disputeLinkDownload(reason);
        accessLogger.log(AccessEvent.anonymous(AccessEventType.DISPUTE_LINK_REJECTED, tenantId, null,
                Map.of("reason", reason, "ip", String.valueOf(clientIp))));
        throw ResourceNotFoundException.of("dispute_evidence_link", null);
    }

    /** Nome no ZIP sem caminho e sem repetição. */
    static String uniqueName(Set<String> used, int index, String original) {
        String base = original == null ? "evidencia" : original.replaceAll("[\\\\/:*?\"<>|]", "_");
        String name = base;
        if (!used.add(name)) {
            name = index + "-" + base;
            used.add(name);
        }
        return name;
    }
}
