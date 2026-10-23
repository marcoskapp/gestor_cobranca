package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.InstructionType;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Cancelamento no PSP de uma cobrança Pix cancelada localmente ({@code payment.instruction_cancel}):
 * {@code PATCH /cob[v]/{txid}} com {@code REMOVIDA_PELO_USUARIO_RECEBEDOR}. Recusa definitiva fica
 * registrada no log técnico (a cobrança expira no PSP); um pagamento que ainda chegar é aceito e alocado.
 */
@Component
class InstructionCancellationJob implements JobHandler<InstructionCancellationJob.Payload> {

    static final String TYPE = "payment.instruction_cancel";
    private static final Logger LOG = LoggerFactory.getLogger(InstructionCancellationJob.class);

    private final PaymentInstructionRepository instructions;
    private final PixAccountRepository accounts;
    private final PixGateway pixGateway;
    private final PaymentMetrics metrics;

    InstructionCancellationJob(PaymentInstructionRepository instructions, PixAccountRepository accounts,
                               PixGateway pixGateway, PaymentMetrics metrics) {
        this.instructions = instructions;
        this.accounts = accounts;
        this.pixGateway = pixGateway;
        this.metrics = metrics;
    }

    record Payload(UUID instructionId) {
    }

    static void enqueue(JobQueue jobs, UUID instructionId) {
        jobs.enqueue(TYPE, new Payload(instructionId), null, "instruction-cancel:" + instructionId);
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
        return 5;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        PaymentInstruction instruction = instructions.findById(payload.instructionId()).orElse(null);
        if (instruction == null || instruction.getStatus() != InstructionStatus.CANCELLED || !instruction.isPix()) {
            return;
        }
        PixAccount account = accounts.findById(instruction.getPixAccountId()).orElse(null);
        if (account == null) {
            return;
        }
        try {
            pixGateway.provider(account).cancelCharge(pixGateway.connection(account), instruction.getTxid(),
                    instruction.getType() == InstructionType.PIX_DUE);
            metrics.instruction(instruction.getType().name(), "provider_cancelled");
        } catch (ProviderException e) {
            if (e.isTransientFailure()) {
                throw e;
            }
            metrics.instruction(instruction.getType().name(), "provider_cancel_refused");
            LOG.atWarn().addKeyValue("instructionId", instruction.getId()).addKeyValue("code", e.code())
                    .log("PSP recusou o cancelamento da cobrança; ela expira no prazo original");
        }
    }
}
