package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.application.port.out.LegalDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Índice: {@code ix_legal_matter_debtor (tenant_id, debtor_id)}; escritório pela chave primária. Só o escritório atual
 * do caso: a troca de escritório não guarda histórico próprio (fica na trilha de auditoria).
 */
@Component
class JdbcLegalDataSharingQueries implements LegalDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLegalDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> lawFirmsOf(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                select null as code, f.id as recipient_id, f.name as name,
                       min(m.referred_at) as first_at, max(coalesce(m.closed_at, m.stage_changed_at)) as last_at,
                       count(*) as total, (array_agg(m.id order by m.referred_at desc))[1] as latest_id
                  from legal.legal_matter m
                  join legal.law_firm f on f.id = m.law_firm_id
                 where m.debtor_id = :debtor and m.responsible_type = 'LAW_FIRM'
                """);
        if (creditorScope != null) {
            sql.append(" and m.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by f.id, f.name order by min(m.referred_at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }
}
