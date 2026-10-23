package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.DivergenceSource;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import br.com.gestorcobranca.payment.domain.repository.DivergenceRepository;
import br.com.gestorcobranca.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Abertura de divergências na fila de análise (idempotente por tipo e referência do provedor). */
@Component
class Divergences {

    private static final Logger LOG = LoggerFactory.getLogger(Divergences.class);

    private final DivergenceRepository divergences;
    private final PaymentRecorder recorder;

    Divergences(DivergenceRepository divergences, PaymentRecorder recorder) {
        this.divergences = divergences;
        this.recorder = recorder;
    }

    Optional<Divergence> open(DivergenceType type, DivergenceSource source, UUID creditorId, UUID paymentId,
                              UUID instructionId, UUID statementLineId, String providerReference, Money amount,
                              Money expected, LocalDate occurredOn) {
        if (providerReference != null && divergences.existsByTypeAndProviderReference(type, providerReference)) {
            return Optional.empty();
        }
        Divergence d = divergences.save(new Divergence(type, source, creditorId, paymentId, instructionId,
                statementLineId, providerReference, amount, expected, occurredOn));
        recorder.divergenceOpened(d);
        LOG.atWarn().addKeyValue("divergenceId", d.getId()).addKeyValue("type", type).addKeyValue("source", source)
                .addKeyValue("paymentId", paymentId).log("Divergência de conciliação aberta para análise");
        return Optional.of(d);
    }
}
