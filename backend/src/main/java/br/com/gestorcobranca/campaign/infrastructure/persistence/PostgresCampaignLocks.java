package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.application.port.out.CampaignLocks;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** {@link CampaignLocks} com {@code pg_advisory_xact_lock} (chave de 64 bits por tenant e devedor). */
@Component
class PostgresCampaignLocks implements CampaignLocks {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;

    PostgresCampaignLocks(JdbcTemplate jdbcTemplate, CurrentActor currentActor) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockDebtor(UUID debtorId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "campaign.debtor:" + currentActor.tenantId() + ":" + debtorId);
    }
}
