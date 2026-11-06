package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.portal.application.service.PortalTestHooks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retenção dos dados de acesso ao portal (LGPD, minimização) contra PostgreSQL real: desafios vencidos — inclusive
 * o fictício, com o HMAC do documento de quem não é devedor — e contadores do bloqueio progressivo sem uso são
 * apagados; IP e agente das sessões encerradas há mais do prazo (1 ano) são apagados e a sessão continua (funil).
 */
class PortalRetentionIT extends PortalTestSupport {

    @BeforeEach
    void setUp() {
        provision("pret");
    }

    @Test
    void purgesExpiredChallengesIdleThrottlesAndOldSessionClientData() {
        UUID decoy = requestCode(CPF_UNKNOWN);
        assertThat(count("select count(*) from portal.access_challenge where id = ? and debtor_id is null "
                + "and document_hash is not null", decoy)).isEqualTo(1);
        loginReady(CPF_MARIA);
        loginReady(CPF_MARIA);
        List<UUID> sessions = system(() -> jdbcTemplate.queryForList(
                "select id from portal.portal_session where tenant_id = ? order by created_at, id", UUID.class, tenant));
        assertThat(sessions).hasSize(2);
        assertThat(count("select count(*) from portal.portal_session where tenant_id = ? and client_ip is not null "
                + "and user_agent is not null", tenant)).isEqualTo(2);

        // tudo o que existe até aqui envelhece além dos prazos (30 dias desafios/contadores; 1 ano IP e agente); a
        // segunda sessão passa dos 180 dias do prazo anterior, mas não de 1 ano (decisão do responsável sobre a 874)
        system(() -> {
            jdbcTemplate.update("update portal.access_challenge set expires_at = now() - interval '31 days' "
                    + "where tenant_id = ?", tenant);
            jdbcTemplate.update("update portal.access_throttle set updated_at = now() - interval '31 days' "
                    + "where tenant_id = ?", tenant);
            jdbcTemplate.update("update portal.portal_session set status = 'ENDED', end_reason = 'logout', "
                    + "ended_at = now() - interval '366 days' where id = ?", sessions.get(0));
            jdbcTemplate.update("update portal.portal_session set status = 'ENDED', end_reason = 'logout', "
                    + "ended_at = now() - interval '200 days' where id = ?", sessions.get(1));
            return null;
        });
        // desafio fictício recente (dentro do prazo): fica, e os contadores que ele tocou ficam recentes
        UUID recentDecoy = requestCode(CPF_UNKNOWN);
        // contador antigo (documento da Maria), mas com bloqueio vigente: fica
        assertThat(system(() -> jdbcTemplate.update("update portal.access_throttle set locked_until = now() + interval "
                + "'1 hour' where id = (select id from portal.access_throttle where tenant_id = ? and key_type = 'DOCUMENT' "
                + "and updated_at < now() - interval '30 days' order by id limit 1)", tenant))).isEqualTo(1);
        long throttlesBefore = count("select count(*) from portal.access_throttle where tenant_id = ?", tenant);
        long freshThrottles = count("select count(*) from portal.access_throttle where tenant_id = ? "
                + "and updated_at > now() - interval '1 day'", tenant);
        long challengesBefore = count("select count(*) from portal.access_challenge where tenant_id = ?", tenant);

        int purged = PortalTestHooks.purgeAccessData(applicationContext, tenant);

        assertThat(count("select count(*) from portal.access_challenge where id = ?", decoy)).isZero();
        assertThat(count("select count(*) from portal.access_challenge where tenant_id = ?", tenant)).isEqualTo(1);
        assertThat(count("select count(*) from portal.access_challenge where id = ?", recentDecoy)).isEqualTo(1);
        long lockedOld = count("select count(*) from portal.access_throttle where tenant_id = ? "
                + "and locked_until > now() and updated_at < now() - interval '30 days'", tenant);
        assertThat(count("select count(*) from portal.access_throttle where tenant_id = ?", tenant))
                .isEqualTo(freshThrottles + lockedOld);
        assertThat(count("select count(*) from portal.access_throttle where tenant_id = ? "
                + "and updated_at < now() - interval '30 days' and (locked_until is null or locked_until < now())",
                tenant)).isZero();
        // a sessão de mais de 1 ano continua (funil), sem IP nem agente; a de 200 dias fica intacta
        assertThat(count("select count(*) from portal.portal_session where id = ? and client_ip is null "
                + "and user_agent is null", sessions.get(0))).isEqualTo(1);
        assertThat(count("select count(*) from portal.portal_session where id = ? and client_ip is not null "
                + "and user_agent is not null", sessions.get(1))).isEqualTo(1);
        long throttlesPurged = throttlesBefore - freshThrottles - lockedOld;
        assertThat(purged).isEqualTo((int) ((challengesBefore - 1) + throttlesPurged + 1));
        // segunda execução: nada mais a descartar
        assertThat(PortalTestHooks.purgeAccessData(applicationContext, tenant)).isZero();
    }
}
