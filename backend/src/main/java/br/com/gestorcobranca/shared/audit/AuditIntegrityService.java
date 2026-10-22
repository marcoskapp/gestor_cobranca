package br.com.gestorcobranca.shared.audit;

import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sela a trilha de auditoria (encadeamento de hashes por tenant) e verifica sua integridade.
 * <p>
 * A selagem é assíncrona para não serializar as gravações de auditoria: as inserções só
 * calculam o {@code content_hash}; este job, executando em uma única instância (ShedLock),
 * percorre os registros não selados em ordem de {@code seq} e grava
 * {@code chain_hash = sha256(chain_hash_anterior || content_hash)}.
 */
@Component
public class AuditIntegrityService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditIntegrityService.class);
    private static final int BATCH = 2_000;

    private final JdbcTemplate jdbcTemplate;
    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final MeterRegistry meterRegistry;

    public AuditIntegrityService(JdbcTemplate jdbcTemplate, TenantDirectory tenantDirectory,
                                 TenantExecution tenantExecution, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.audit.seal-interval:PT1M}", initialDelayString = "PT30S")
    @SchedulerLock(name = "audit.seal", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void sealAll() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int sealed;
                do {
                    sealed = tenantExecution.callInTenant(tenantId, "audit.seal", () -> sealBatch(tenantId));
                    meterRegistry.counter("audit.sealed").increment(sealed);
                } while (sealed == BATCH);
            } catch (RuntimeException e) {
                meterRegistry.counter("audit.seal.failures").increment();
                LOG.error("Falha ao selar auditoria do tenant {}", tenantId, e);
            }
        }
    }

    /** Executado dentro da transação aberta por {@link TenantExecution#callInTenant}. */
    private int sealBatch(UUID tenantId) {
        String previous = jdbcTemplate.query("""
                        select chain_hash from shared.audit_event
                        where tenant_id = ? and chain_hash is not null
                        order by seq desc limit 1
                        """, rs -> rs.next() ? rs.getString(1) : AuditHashing.GENESIS, tenantId);

        List<Object[]> pending = jdbcTemplate.query("""
                        select id, content_hash from shared.audit_event
                        where tenant_id = ? and chain_hash is null
                        order by seq limit ?
                        """, (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}, tenantId, BATCH);

        String chain = previous;
        Instant now = Instant.now();
        for (Object[] row : pending) {
            chain = AuditHashing.chainHash(chain, (String) row[1]);
            jdbcTemplate.update("update shared.audit_event set chain_hash = ?, sealed_at = ? where id = ?",
                    chain, java.sql.Timestamp.from(now), row[0]);
        }
        return pending.size();
    }

    /**
     * Recalcula a cadeia do tenant corrente e retorna o primeiro registro inconsistente, se
     * houver. Requer contexto de tenant (chamado pelo caso de uso de auditoria).
     */
    @Transactional(readOnly = true)
    public Optional<IntegrityViolation> verifyCurrentTenant() {
        // leitura em streaming (fetch size) para não carregar a trilha inteira em memória
        JdbcTemplate streaming = new JdbcTemplate(jdbcTemplate.getDataSource());
        streaming.setFetchSize(1_000);
        String[] chain = {AuditHashing.GENESIS};
        long[] previousSeq = {-1};
        IntegrityViolation[] violation = {null};
        streaming.query("""
                select a.seq, a.id, a.content_hash, a.chain_hash,
                       a.content_hash = shared.audit_content_hash(a) as content_ok
                from shared.audit_event a
                where a.chain_hash is not null
                order by a.seq
                """, rs -> {
            if (violation[0] != null) {
                return;
            }
            long seq = rs.getLong(1);
            UUID id = rs.getObject(2, UUID.class);
            String contentHash = rs.getString(3);
            if (!rs.getBoolean(5)) {
                violation[0] = new IntegrityViolation(seq, id, "content_hash divergente: registro alterado");
                return;
            }
            chain[0] = AuditHashing.chainHash(chain[0], contentHash);
            if (!chain[0].equals(rs.getString(4))) {
                violation[0] = new IntegrityViolation(seq, id,
                        "chain_hash divergente: registro removido ou reordenado após seq " + previousSeq[0]);
                return;
            }
            previousSeq[0] = seq;
        });
        return Optional.ofNullable(violation[0]);
    }

    public record IntegrityViolation(long seq, UUID auditEventId, String reason) {
    }
}
