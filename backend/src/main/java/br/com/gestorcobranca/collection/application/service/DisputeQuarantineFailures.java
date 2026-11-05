package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Última tentativa da verificação de malware sem veredito: marca o documento FAILED em transação própria. */
@Component
class DisputeQuarantineFailures {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeQuarantineFailures.class);

    private final DisputeDocumentQuarantineRepository quarantine;
    private final Clock clock;

    DisputeQuarantineFailures(DisputeDocumentQuarantineRepository quarantine, Clock clock) {
        this.quarantine = quarantine;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exhausted(UUID documentId) {
        quarantine.findById(documentId).filter(d -> d.isPending()).ifPresent(d -> {
            d.failed(Instant.now(clock));
            quarantine.save(d);
            LOG.atError().addKeyValue("documentId", documentId)
                    .log("Documento do credor não verificado após todas as tentativas; continua retido na quarentena");
        });
    }
}
