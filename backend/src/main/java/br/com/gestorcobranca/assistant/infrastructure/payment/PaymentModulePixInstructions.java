package br.com.gestorcobranca.assistant.infrastructure.payment;

import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de {@link PaymentInstructionPort} sobre a interface pública do payment (ADR-0009: o assistant
 * depende só de {@code payment :: usecases}). Sempre {@code PIX_IMMEDIATE} com valor explícito (o saldo da
 * parcela pelo motor — o payment ainda recusa valor acima do saldo); permissão, escopo por credor, conta Pix
 * do credor/assessoria, idempotência e reutilização de instrução aberta igual ficam no payment. Roda no
 * contexto do ator {@code ASSISTANT} do chamador.
 */
@Component
class PaymentModulePixInstructions implements PaymentInstructionPort {

    static final String TYPE = "PIX_IMMEDIATE";

    private final PaymentInstructionUseCases instructions;
    private final Clock clock;

    PaymentModulePixInstructions(PaymentInstructionUseCases instructions, Clock clock) {
        this.instructions = instructions;
        this.clock = clock;
    }

    @Override
    public PixInstruction issueImmediatePix(IssuePixCommand command) {
        Instant started = Instant.now(clock);
        InstructionView view = instructions.create(new CreateInstructionCommand(TYPE, command.agreementId(),
                command.installmentId(), null, command.amount(), null, command.expiresInSeconds(), command.channel()),
                command.idempotencyKey());
        if (!TYPE.equals(view.type()) || !command.installmentId().equals(view.installmentId())
                || view.amount().compareTo(command.amount()) != 0) {
            throw new IllegalStateException("Instrução devolvida pelo payment não corresponde ao pedido do assistente");
        }
        // reutilizada: o payment devolveu uma instrução aberta criada antes deste pedido
        return map(view, view.createdAt() != null && view.createdAt().isBefore(started));
    }

    @Override
    public Optional<PixInstruction> find(UUID instructionId) {
        try {
            return Optional.of(map(instructions.get(instructionId), false));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cancel(UUID instructionId, String reason) {
        instructions.cancel(instructionId, reason);
    }

    private static PixInstruction map(InstructionView v, boolean reused) {
        return new PixInstruction(v.id(), v.type(), v.status(), v.creditorId(), v.debtorId(), v.agreementId(),
                v.installmentId(), v.installmentNumber(), v.amount(), v.expiresAt(), v.pixCopyPaste(), v.createdAt(),
                v.paidAt(), reused);
    }
}
