package br.com.gestorcobranca.shared.idempotency;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotência de comandos que criam recursos (registro de pagamento externo, formalização de
 * acordo, importação via API). O cliente envia o cabeçalho {@code Idempotency-Key}; repetições
 * com a mesma chave e o mesmo conteúdo devolvem o recurso já criado, e repetições com conteúdo
 * diferente são recusadas.
 * <p>
 * Deve ser chamado dentro da transação do caso de uso: a chave e o recurso são confirmados
 * juntos. Corridas simultâneas com a mesma chave são resolvidas pelo índice único (uma delas
 * recebe erro de integridade e é desfeita).
 */
@Component
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;

    public IdempotencyService(JdbcTemplate jdbcTemplate, CurrentActor currentActor) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
    }

    /**
     * @param scope       operação (ex.: {@code payment.external})
     * @param key         chave enviada pelo cliente; se nula, executa sem idempotência
     * @param requestHash hash estável do conteúdo do comando
     * @param action      cria o recurso e devolve seu id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID execute(String scope, String key, String requestHash, Supplier<UUID> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (key.length() > 128) {
            throw new ConflictException("idempotency.key_too_long", "Idempotency-Key deve ter até 128 caracteres");
        }
        UUID tenantId = currentActor.tenantId();
        List<Object[]> existing = jdbcTemplate.query("""
                        select request_hash, resource_id from shared.idempotency_key
                        where tenant_id = ? and scope = ? and idem_key = ?
                        """, (rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, UUID.class)},
                tenantId, scope, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst()[0].equals(requestHash)) {
                throw new ConflictException("idempotency.key_reused",
                        "Idempotency-Key já utilizada com conteúdo diferente");
            }
            return (UUID) existing.getFirst()[1];
        }
        UUID resourceId = action.get();
        jdbcTemplate.update("""
                insert into shared.idempotency_key (tenant_id, scope, idem_key, request_hash, resource_id, created_at, created_by)
                values (?, ?, ?, ?, ?, now(), ?)
                """, tenantId, scope, key, requestHash, resourceId, currentActor.actorId());
        return resourceId;
    }
}
