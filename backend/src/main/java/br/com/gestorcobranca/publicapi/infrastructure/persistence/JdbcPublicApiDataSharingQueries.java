package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.application.port.out.PublicApiDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Índice: {@code ix_debt_title_debtor (tenant_id, debtor_id)}; lote e token pela chave primária. O título guarda o
 * último lote que o gravou ({@code debt_title.import_batch_id}): lotes anteriores do mesmo título não aparecem. A API não
 * registra consulta por devedor (o log de acesso guarda a rota, não o id), então só a carga é rastreável.
 */
@Component
class JdbcPublicApiDataSharingQueries implements PublicApiDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPublicApiDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> tokensOf(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                select null as code, k.id as recipient_id, k.name as name, min(b.created_at) as first_at,
                       max(b.created_at) as last_at, count(distinct b.batch_id) as total, null::uuid as latest_id
                  from debt.debt_title t
                  join publicapi.api_import_batch b on b.batch_id = t.import_batch_id
                  join publicapi.integration_token k on k.id = b.token_id
                 where t.debtor_id = :debtor
                """);
        if (creditorScope != null) {
            sql.append(" and t.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by k.id, k.name order by min(b.created_at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }
}
