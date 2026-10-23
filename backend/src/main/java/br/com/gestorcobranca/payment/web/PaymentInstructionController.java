package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Instruções de pagamento (Pix imediato/com vencimento; boleto e link aguardam provedor). */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/instructions")
class PaymentInstructionController {

    private final PaymentInstructionUseCases instructions;

    PaymentInstructionController(PaymentInstructionUseCases instructions) {
        this.instructions = instructions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_INSTRUCTION_CREATE')")
    InstructionView create(@Valid @RequestBody CreateRequest request,
                           @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return instructions.create(new CreateInstructionCommand(request.type(), request.agreementId(),
                request.installmentId(), request.titleId(), request.amount(), request.dueDate(),
                request.expiresInSeconds(), request.channel()), idempotencyKey);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    PageResponse<InstructionView> search(@RequestParam(required = false) UUID creditorId,
                                         @RequestParam(required = false) UUID debtorId,
                                         @RequestParam(required = false) UUID agreementId,
                                         @RequestParam(required = false) UUID titleId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return instructions.search(new InstructionFilter(creditorId, debtorId, agreementId, titleId, status),
                PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    InstructionView get(@PathVariable UUID id) {
        return instructions.get(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAYMENT_INSTRUCTION_CREATE')")
    InstructionView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return instructions.cancel(id, request.reason());
    }

    record CreateRequest(@NotBlank @Size(max = 20) String type, UUID agreementId, UUID installmentId, UUID titleId,
                         @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount, LocalDate dueDate,
                         Integer expiresInSeconds, @Size(max = 20) String channel) {
    }

    record CancelRequest(@NotBlank @Size(max = 200) String reason) {
    }
}
