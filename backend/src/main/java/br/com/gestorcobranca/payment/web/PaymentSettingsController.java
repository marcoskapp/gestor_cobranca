package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases.SettingsView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** Parâmetros de pagamentos da assessoria (limite de revisão, validade do Pix, tolerância de datas). */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/settings")
class PaymentSettingsController {

    private final PaymentSettingsUseCases settings;

    PaymentSettingsController(PaymentSettingsUseCases settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW') or hasAuthority('ORG_SETTINGS_MANAGE')")
    SettingsView get() {
        return settings.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    SettingsView update(@Valid @RequestBody SettingsRequest request) {
        return settings.update(new SettingsCommand(request.externalReviewThreshold(), request.pixExpirationSeconds(),
                request.pixDueValidityDays(), request.reconciliationDateToleranceDays()));
    }

    record SettingsRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal externalReviewThreshold,
                           @Min(300) @Max(2_592_000) int pixExpirationSeconds,
                           @Min(0) @Max(60) int pixDueValidityDays,
                           @Min(0) @Max(15) int reconciliationDateToleranceDays) {
    }
}
