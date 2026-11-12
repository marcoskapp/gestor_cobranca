package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Base das taxas da inteligência de contato ({@code scoring.contact_rate_base}), recalculada na manutenção diária,
 * por assessoria: tentativas e sucessos dos últimos {@code 180} dias por carteira e canal e por assessoria e canal
 * (priori da taxa suavizada) e a mediana de tentativas até o 1º sucesso por carteira. O cartão do perfil só lê a
 * tabela — abrir o cartão não agrega a assessoria inteira. Antes da primeira manutenção a tabela está vazia e a
 * priori é neutra (50%).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ContactRateBaseService {

    private static final Logger LOG = LoggerFactory.getLogger(ContactRateBaseService.class);
    /** Janela das tentativas da priori e da mediana. */
    static final Duration RATE_WINDOW = Duration.ofDays(180);

    private final ContactFactStore store;
    private final ScoringMetrics metrics;
    private final Clock clock;

    ContactRateBaseService(ContactFactStore store, ScoringMetrics metrics, Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Recalcula a base do tenant da transação. @return linhas gravadas */
    int refresh() {
        long started = System.nanoTime();
        Instant now = Instant.now(clock);
        int rows = store.refreshRateBase(now.minus(RATE_WINDOW), now);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        metrics.contactRateBaseRefreshed(took);
        LOG.atInfo().addKeyValue("process", "scoring.contact_rate_base").addKeyValue("rows", rows)
                .addKeyValue("durationMs", took.toMillis())
                .log("Base das taxas da inteligência de contato recalculada");
        return rows;
    }
}
