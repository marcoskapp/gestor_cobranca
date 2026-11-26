package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatchRow;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Chave HMAC do identificador do título em cada linha gravada da carga (decisão 1476): a mesma que o debt calcula para o
 * título ({@link ExternalIdDigest}), para a detecção nos arquivos achar as linhas do titular anonimizado (inclusive as
 * sem gravação — prévia, erro) depois que o identificador do título vira {@code anon:}.
 */
@Component
class ImportRowTitleKeys {

    private final ExternalIdDigest digest;
    private final CurrentActor currentActor;

    ImportRowTitleKeys(ExternalIdDigest digest, CurrentActor currentActor) {
        this.digest = digest;
        this.currentActor = currentActor;
    }

    <T extends Iterable<ImportBatchRow>> T assign(UUID creditorId, T rows) {
        UUID tenantId = currentActor.tenantId();
        for (ImportBatchRow row : rows) {
            String key = row.getTitleKey() == null ? digest.key(tenantId, Kind.TITLE, creditorId, row.getExternalId()) : null;
            // nunca lança dentro do bloco da carga: sem forma canônica, a linha fica sem chave (check do banco)
            if (key != null) {
                row.assignTitleKey(key);
            }
        }
        return rows;
    }

    ImportBatchRow assign(UUID creditorId, ImportBatchRow row) {
        assign(creditorId, List.of(row));
        return row;
    }
}
