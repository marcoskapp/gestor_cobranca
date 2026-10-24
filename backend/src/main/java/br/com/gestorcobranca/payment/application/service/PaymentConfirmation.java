package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.service.AllocationService.Destination;
import br.com.gestorcobranca.payment.application.service.AllocationService.Result;
import br.com.gestorcobranca.payment.domain.model.AllocationOrigin;
import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.model.TitleStrategy;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.UUID;

/**
 * Efeitos de um pagamento que acabou de ficar {@code CONFIRMED} (provedor, extrato, registro de baixo
 * risco ou aprovação): alocação ao destino (instrução ou destino informado), instrução paga, eventos
 * de integração por alocação, fato de negócio, auditoria e métricas — tudo na transação da confirmação.
 */
@Component
class PaymentConfirmation {

    private static final TypeReference<List<UUID>> IDS = new TypeReference<>() {
    };

    private final PaymentRepository payments;
    private final PaymentInstructionRepository instructions;
    private final AllocationService allocationService;
    private final PaymentRecorder recorder;
    private final JsonCodec json;

    PaymentConfirmation(PaymentRepository payments, PaymentInstructionRepository instructions,
                        AllocationService allocationService, PaymentRecorder recorder, JsonCodec json) {
        this.payments = payments;
        this.instructions = instructions;
        this.allocationService = allocationService;
        this.recorder = recorder;
        this.json = json;
    }

    /**
     * @param instruction instrução paga (pagamento integrado), ou nula
     * @param trigger     provider, statement, auto_low_risk, review
     */
    void confirmed(Payment payment, PaymentInstruction instruction, String trigger) {
        Destination destination = instruction != null ? Destination.of(instruction) : requested(payment);
        Result result = destination == null ? new Result(List.of(), payment.getAmount(), "no_target")
                : allocationService.allocate(payment, destination, payment.getAmount(), AllocationOrigin.INITIAL, null);
        payments.save(payment);
        if (instruction != null) {
            InstructionStatus before = instruction.getStatus();
            instruction.markPaid(payment.getId(), payment.getPaidAt());
            instructions.save(instruction);
            recorder.instructionStatusChanged(instruction, before);
        }
        recorder.confirmed(payment, result.allocations(), result.leftover(), result.leftoverReason(), trigger, instruction);
    }

    Destination requested(Payment payment) {
        if (payment.getTargetKind() == null) {
            return null;
        }
        List<UUID> items = payment.getTargetItems() == null ? List.of() : json.fromJson(payment.getTargetItems(), IDS);
        return new Destination(payment.getTargetKind(), payment.getTargetAgreementId(), items,
                payment.getTargetKind() == TargetKind.TITLES
                        ? (payment.getTargetStrategy() == null ? TitleStrategy.OLDEST_FIRST : payment.getTargetStrategy())
                        : null);
    }

    static boolean hasLeftover(Payment payment) {
        return payment.unallocated().isGreaterThan(Money.ZERO);
    }
}
