package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixTransaction;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.jobs.PermanentJobFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Confirmação de um Pix notificado por consulta ao PSP ({@code payment.pix_sync}, {@code GET
 * /pix/{e2eid}}): o pagamento só é criado com os dados devolvidos pelo PSP (valor, {@code txid},
 * horário e devoluções), nunca com os da notificação. PSP que ainda não conhece o Pix → nova tentativa;
 * esgotadas, o job vai para {@code DEAD} (alarme {@code payment.jobs.failures{job=pix_sync}}).
 */
@Component
class PixSyncJob implements JobHandler<PixSyncJob.Payload> {

    static final String TYPE = "payment.pix_sync";
    private static final int MAX_ATTEMPTS = 8;
    private static final Logger LOG = LoggerFactory.getLogger(PixSyncJob.class);

    private final PixAccountRepository accounts;
    private final PixGateway pixGateway;
    private final PixSettlement settlement;
    private final PaymentMetrics metrics;

    PixSyncJob(PixAccountRepository accounts, PixGateway pixGateway, PixSettlement settlement, PaymentMetrics metrics) {
        this.accounts = accounts;
        this.pixGateway = pixGateway;
        this.settlement = settlement;
        this.metrics = metrics;
    }

    record Payload(UUID pixAccountId, String endToEndId) {
    }

    static void enqueue(JobQueue jobs, UUID pixAccountId, String endToEndId) {
        jobs.enqueue(TYPE, new Payload(pixAccountId, endToEndId), null, "pix:" + endToEndId);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        PixAccount account = accounts.findById(payload.pixAccountId()).orElse(null);
        if (account == null) {
            throw new PermanentJobFailure("Conta Pix inexistente: " + payload.pixAccountId());
        }
        Optional<PixTransaction> transaction;
        try {
            transaction = pixGateway.provider(account).findPix(pixGateway.connection(account), payload.endToEndId());
        } catch (ProviderException e) {
            if (!e.isTransientFailure() || context.attempt() >= MAX_ATTEMPTS) {
                metrics.jobFailure("pix_sync");
                throw new PermanentJobFailure("PSP recusou a consulta do Pix (" + e.code() + ")", e);
            }
            throw e;
        }
        if (transaction.isEmpty()) {
            if (context.attempt() >= MAX_ATTEMPTS) {
                metrics.jobFailure("pix_sync");
                metrics.pixSync("not_found");
                LOG.atError().addKeyValue("pixAccountId", account.getId()).addKeyValue("attempts", context.attempt())
                        .log("Pix notificado não confirmado pelo PSP após todas as tentativas");
                throw new PermanentJobFailure("Pix notificado não encontrado no PSP");
            }
            throw new ProviderException("PIX_NOT_FOUND", "PSP ainda não devolve o Pix notificado", true, null);
        }
        if (!transaction.get().endToEndId().equals(payload.endToEndId())) {
            metrics.jobFailure("pix_sync");
            throw new PermanentJobFailure("PSP devolveu outro endToEndId para a consulta");
        }
        settlement.apply(account, transaction.get());
    }
}
