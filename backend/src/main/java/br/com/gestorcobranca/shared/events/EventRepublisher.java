package br.com.gestorcobranca.shared.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reentrega publicações de eventos cujos consumidores falharam (ex.: banco indisponível,
 * erro transitório). Executa em uma única instância por vez. O gauge
 * {@code events.publications.incomplete} alimenta alarme no CloudWatch: crescimento contínuo
 * indica um consumidor quebrado.
 */
@Component
class EventRepublisher {

    private static final Logger LOG = LoggerFactory.getLogger(EventRepublisher.class);

    private final IncompleteEventPublications incompletePublications;
    private final Duration minAge;

    EventRepublisher(IncompleteEventPublications incompletePublications, JdbcTemplate jdbcTemplate,
                     MeterRegistry meterRegistry,
                     @Value("${app.events.republish-min-age:PT2M}") Duration minAge) {
        this.incompletePublications = incompletePublications;
        this.minAge = minAge;
        Gauge.builder("events.publications.incomplete", jdbcTemplate,
                        jdbc -> {
                            Long count = jdbc.queryForObject(
                                    "select count(*) from public.event_publication where completion_date is null", Long.class);
                            return count == null ? 0 : count;
                        })
                .description("Publicações de eventos de integração ainda não concluídas")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.events.republish-interval:PT1M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "events.republish", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    void republish() {
        LOG.debug("Reenviando publicações incompletas com mais de {}", minAge);
        incompletePublications.resubmitIncompletePublicationsOlderThan(minAge);
    }
}
