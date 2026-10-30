package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.application.port.out.ApiImportBatchStore;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class JdbcApiImportBatchStore implements ApiImportBatchStore {

    private final JdbcTemplate jdbc;
    private final CurrentActor currentActor;

    JdbcApiImportBatchStore(JdbcTemplate jdbc, CurrentActor currentActor) {
        this.jdbc = jdbc;
        this.currentActor = currentActor;
    }

    @Override
    public void register(UUID batchId, UUID tokenId, UUID creditorId) {
        jdbc.update("""
                insert into publicapi.api_import_batch (batch_id, tenant_id, token_id, creditor_id, created_at)
                values (?, ?, ?, ?, now()) on conflict (batch_id) do nothing
                """, batchId, currentActor.tenantId(), tokenId, creditorId);
    }

    @Override
    public Optional<Origin> find(UUID batchId) {
        return jdbc.query("select token_id, creditor_id from publicapi.api_import_batch where batch_id = ?",
                (rs, i) -> new Origin(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), batchId)
                .stream().findFirst();
    }
}
