package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.application.port.out.CommunicationDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_conversation_debtor (tenant_id, debtor_id, last_message_at desc)} e {@code ix_message_conversation
 * (tenant_id, conversation_id, created_at)}; conta de canal pela chave primária. Conta só a mensagem que passou pelo
 * provedor: recebida ({@code INBOUND}) ou enviada a ele ({@code provider_message_id} ou {@code sent_at}); a que ficou na
 * fila ou falhou antes do envio (porteiro, conta inativa) não saiu da assessoria.
 */
@Component
class JdbcCommunicationDataSharingQueries implements CommunicationDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCommunicationDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> messagesByProvider(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                select a.provider as code, null::uuid as recipient_id, min(a.name) as name,
                       min(m.created_at) as first_at, max(m.created_at) as last_at, count(*) as total,
                       (array_agg(c.id order by m.created_at desc))[1] as latest_id
                  from communication.conversation c
                  join communication.message m on m.conversation_id = c.id
                  join communication.channel_account a on a.id = m.channel_account_id
                 where c.debtor_id = :debtor
                   and (m.direction = 'INBOUND' or m.provider_message_id is not null or m.sent_at is not null)
                """);
        if (creditorScope != null) {
            sql.append(" and c.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by a.provider order by min(m.created_at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }

    @Override
    public List<String> activeProviders() {
        return jdbc.queryForList("select distinct provider from communication.channel_account where active order by 1",
                new MapSqlParameterSource(), String.class);
    }
}
