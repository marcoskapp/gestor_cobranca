package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.DisputeMailPurpose;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Última tentativa transitória do e-mail ao credor: marca a falha em transação própria (a do job é desfeita). */
@Component
class DisputeMailFailures {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeMailFailures.class);

    private final DisputeMailRepository mails;
    private final DisputeCreditorRequestRepository requests;
    private final Clock clock;

    DisputeMailFailures(DisputeMailRepository mails, DisputeCreditorRequestRepository requests, Clock clock) {
        this.mails = mails;
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exhausted(UUID mailId, int attempts) {
        mails.findById(mailId).filter(m -> m.isQueued()).ifPresent(mail -> {
            mail.recordAttempt(attempts);
            mail.markFailed("RETRIES_EXHAUSTED", Instant.now(clock));
            mails.save(mail);
            if (mail.getPurpose() == DisputeMailPurpose.REQUEST) {
                requests.findById(mail.getRequestId()).ifPresent(r -> {
                    r.markFailed();
                    requests.save(r);
                });
            }
            LOG.atError().addKeyValue("mailId", mailId).addKeyValue("attempts", attempts)
                    .log("E-mail da contestação ao credor não enviado após todas as tentativas");
        });
    }
}
