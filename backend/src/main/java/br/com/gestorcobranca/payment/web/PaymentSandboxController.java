package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases.SimulatedNotification;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Simulação do PSP (somente com o sandbox habilitado; local/teste). A notificação é entregue ao webhook. */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/sandbox")
@PreAuthorize("hasAuthority('INTEGRATION_MANAGE')")
class PaymentSandboxController {

    private final PaymentSandboxUseCases sandbox;

    PaymentSandboxController(PaymentSandboxUseCases sandbox) {
        this.sandbox = sandbox;
    }

    @PostMapping("/instructions/{id}/pay")
    SimulatedNotification pay(@PathVariable UUID id, @Valid @RequestBody(required = false) AmountRequest request) {
        return sandbox.simulatePixPayment(id, request == null ? null : request.amount(), true);
    }

    @PostMapping("/pix/refund")
    SimulatedNotification refund(@Valid @RequestBody RefundRequest request) {
        return sandbox.simulatePixRefund(request.endToEndId(), request.amount(), true);
    }

    record AmountRequest(@DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    record RefundRequest(@NotBlank @Size(max = 32) String endToEndId,
                         @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }
}
