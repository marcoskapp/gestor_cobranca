package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicImports;
import br.com.gestorcobranca.publicapi.web.security.UsageRecords;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.PaymentDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pagamentos do credor (somente leitura) e registro de pagamento recebido diretamente pelo credor (módulo payment):
 * evidência obrigatória, revisão por segunda pessoa da assessoria acima do limite de risco e baixa só depois da
 * confirmação — as mesmas regras do registro manual.
 */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/payments")
@Tag(name = "Pagamentos", description = "Consulta e pagamento informado pelo credor")
class PaymentController {

    private final PaymentUseCases payments;
    private final PublicImports idempotency;
    private final PublicApiSupport support;

    PaymentController(PaymentUseCases payments, PublicImports idempotency, PublicApiSupport support) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.support = support;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    @Operation(summary = "Pagamentos (sincronização incremental)")
    ListResponse<PaymentDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(payments.changes(support.creditorId(), support.query(updatedSince, cursor, limit)),
                PaymentDto::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    @Operation(summary = "Pagamento")
    PaymentDto get(@PathVariable UUID id) {
        PaymentView view = payments.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("payment", id);
        }
        return PaymentDto.of(view);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_payments:write')")
    @Operation(summary = "Informar pagamento recebido pelo credor", description = "Evidência (PDF, PNG ou JPEG) "
            + "obrigatória. Destino: parcelas de um acordo (`targetKind=AGREEMENT`) ou títulos avulsos (`TITLES`). "
            + "Acima do limite de risco da assessoria o pagamento fica INFORMED até a revisão.",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string")))
    PaymentDto register(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                        @RequestPart("evidence") MultipartFile evidence, @RequestParam @NotNull UUID debtorId,
                        @RequestParam @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                        @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant paidAt,
                        @RequestParam @NotBlank @Pattern(regexp = "PIX|BOLETO|CARD|TRANSFER|CASH|OTHER") String method,
                        @RequestParam @NotBlank @Pattern(regexp = "AGREEMENT|TITLES") String targetKind,
                        @RequestParam(required = false) UUID agreementId,
                        @RequestParam(required = false) @Size(max = 100) List<UUID> itemIds,
                        @RequestParam(required = false) @Pattern(regexp = "OLDEST_FIRST|PROPORTIONAL") String strategy,
                        @RequestParam(required = false) @Size(max = 100) String externalReference,
                        @RequestParam(required = false) @Size(max = 1000) String note,
                        HttpServletRequest http) throws IOException {
        String key = idempotency.scopedIdempotencyKey(idempotencyKey, true);
        PaymentView view = payments.registerExternal(new ExternalPaymentCommand(support.creditorId(), debtorId, amount,
                        paidAt, method, "CREDITOR_DIRECT", externalReference, note,
                        new TargetInput(targetKind, agreementId, itemIds, strategy)),
                new EvidenceUpload(evidence.getOriginalFilename(), evidence.getContentType(), evidence.getSize(),
                        evidence.getInputStream()), key);
        UsageRecords.add(http, 1);
        return PaymentDto.of(view);
    }
}
