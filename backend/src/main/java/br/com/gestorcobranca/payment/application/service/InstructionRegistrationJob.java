package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.payment.application.port.out.PixProvider;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ChargeRequest;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ChargeResult;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.Payer;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.InstructionType;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Registro da instrução no PSP ({@code payment.instruction_register}), como ator de sistema: Pix
 * imediato ({@code cob}, sem dados do pagador — minimização) ou com vencimento ({@code cobv}, com nome
 * e CPF/CNPJ do devedor, exigidos pela API; a leitura do documento é registrada no log de acesso como
 * {@code SENSITIVE_DATA_VIEWED} do ator de sistema). Aceite → {@code ACTIVE}; recusa definitiva →
 * {@code FAILED}; falha transitória → nova tentativa, e na última a instrução fica {@code FAILED}.
 */
@Component
class InstructionRegistrationJob implements JobHandler<InstructionRegistrationJob.Payload> {

    static final String TYPE = "payment.instruction_register";
    private static final int MAX_ATTEMPTS = 6;
    private static final Logger LOG = LoggerFactory.getLogger(InstructionRegistrationJob.class);

    private final PaymentInstructionRepository instructions;
    private final PixAccountRepository accounts;
    private final PixGateway pixGateway;
    private final DebtorUseCases debtors;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final PaymentRecorder recorder;
    private final Clock clock;

    InstructionRegistrationJob(PaymentInstructionRepository instructions, PixAccountRepository accounts,
                               PixGateway pixGateway, DebtorUseCases debtors, AuditTrail auditTrail,
                               PaymentMetrics metrics, PaymentRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.instructions = instructions;
        this.accounts = accounts;
        this.pixGateway = pixGateway;
        this.debtors = debtors;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    record Payload(UUID instructionId) {
    }

    static void enqueue(JobQueue jobs, UUID instructionId) {
        jobs.enqueue(TYPE, new Payload(instructionId), null, "instruction:" + instructionId);
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
        PaymentInstruction instruction = instructions.findByIdForUpdate(payload.instructionId()).orElse(null);
        if (instruction == null || instruction.getStatus() != InstructionStatus.PENDING) {
            return;
        }
        Instant now = Instant.now(clock);
        if (!instruction.getExpiresAt().isAfter(now)) {
            if (instruction.expireIfDue(now)) {
                instructions.save(instruction);
                metrics.instruction(instruction.getType().name(), "expired");
                recorder.instructionStatusChanged(instruction, InstructionStatus.PENDING);
            }
            return;
        }
        PixAccount account = accounts.findById(instruction.getPixAccountId()).filter(PixAccount::isActive).orElse(null);
        if (account == null) {
            fail(instruction, "PIX_ACCOUNT_INACTIVE", "Conta Pix inativa ou removida");
            return;
        }
        PixProvider provider = pixGateway.provider(account);
        boolean due = instruction.getType() == InstructionType.PIX_DUE;
        ChargeRequest request = new ChargeRequest(instruction.getTxid(), due, instruction.getAmount().amount(),
                due ? null : Math.max(1, Duration.between(now, instruction.getExpiresAt()).toSeconds()),
                instruction.getDueDate(), due ? validityAfterDue(instruction) : null,
                due ? payer(instruction.getDebtorId()) : null, description(instruction));
        try {
            ChargeResult result = provider.createCharge(pixGateway.connection(account), request);
            instruction.activate(result.revision(), result.location(), result.copyPaste(), now);
            instructions.save(instruction);
            recorder.instructionStatusChanged(instruction, InstructionStatus.PENDING);
            metrics.instruction(instruction.getType().name(), "registered");
            auditTrail.record(AuditEntry.of("payment_instruction.registered", InstructionService.RESOURCE,
                    instruction.getId()).creditor(instruction.getCreditorId())
                    .change("status", InstructionStatus.PENDING, InstructionStatus.ACTIVE)
                    .meta("provider", account.getProvider()).meta("revision", result.revision()));
        } catch (ProviderException e) {
            if (e.isTransientFailure() && context.attempt() < MAX_ATTEMPTS) {
                LOG.atWarn().addKeyValue("instructionId", instruction.getId()).addKeyValue("code", e.code())
                        .addKeyValue("attempt", context.attempt()).log("Falha transitória ao registrar cobrança Pix");
                throw e;
            }
            fail(instruction, e.code(), e.getMessage());
        }
    }

    private void fail(PaymentInstruction instruction, String code, String detail) {
        instruction.fail(code, detail);
        instructions.save(instruction);
        recorder.instructionStatusChanged(instruction, InstructionStatus.PENDING);
        metrics.instruction(instruction.getType().name(), "failed");
        LOG.atWarn().addKeyValue("instructionId", instruction.getId()).addKeyValue("code", code)
                .log("Cobrança Pix recusada pelo provedor; instrução encerrada como FAILED");
        auditTrail.record(AuditEntry.of("payment_instruction.failed", InstructionService.RESOURCE, instruction.getId())
                .creditor(instruction.getCreditorId()).change("status", InstructionStatus.PENDING, InstructionStatus.FAILED)
                .meta("failureCode", code));
    }

    private Payer payer(UUID debtorId) {
        String name = debtors.get(debtorId).name();
        String document = debtors.revealDocument(debtorId).document().replaceAll("\\D", "");
        return new Payer(document, name);
    }

    /** A expiração da cobrança com vencimento é o início do dia seguinte ao último dia de validade. */
    private static int validityAfterDue(PaymentInstruction i) {
        LocalDate lastDay = i.getExpiresAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate().minusDays(1);
        return (int) Math.max(0, ChronoUnit.DAYS.between(i.getDueDate(), lastDay));
    }

    private static String description(PaymentInstruction i) {
        return i.getInstallmentNumber() != null ? "Parcela " + i.getInstallmentNumber() + " do acordo" : "Pagamento de débito";
    }
}
