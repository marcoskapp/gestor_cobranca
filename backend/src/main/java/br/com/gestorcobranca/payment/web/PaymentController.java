package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceContent;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReallocateCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pagamentos: consulta, registro externo com evidência, revisão, estorno e realocação. */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/payments")
class PaymentController {

    private final PaymentUseCases payments;

    PaymentController(PaymentUseCases payments) {
        this.payments = payments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    PageResponse<PaymentView> search(@RequestParam(required = false) UUID creditorId,
                                     @RequestParam(required = false) UUID debtorId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String method,
                                     @RequestParam(required = false) Instant paidFrom,
                                     @RequestParam(required = false) Instant paidTo,
                                     @RequestParam(required = false) Boolean withBalance,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return payments.search(new PaymentFilter(creditorId, debtorId, status, source, method, paidFrom, paidTo,
                withBalance), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    PaymentView get(@PathVariable UUID id) {
        return payments.get(id);
    }

    /** Pagamento dono de uma alocação (o {@code paymentId} dos eventos e das baixas no acordo). */
    @GetMapping("/by-allocation/{allocationId}")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    PaymentView byAllocation(@PathVariable UUID allocationId) {
        return payments.findByAllocation(allocationId);
    }

    @PostMapping(path = "/external", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_REGISTER_EXTERNAL')")
    PaymentView registerExternal(@Valid @RequestPart("payment") ExternalRequest request,
                                 @RequestPart("evidence") MultipartFile evidence,
                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            return payments.registerExternal(new ExternalPaymentCommand(request.creditorId(), request.debtorId(),
                    request.amount(), request.paidAt(), request.method(), request.origin(), request.externalReference(),
                    request.note(), request.target().input()), new EvidenceUpload(evidence.getOriginalFilename(),
                    evidence.getContentType(), evidence.getSize(), evidence.getInputStream()), idempotencyKey);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler a evidência enviada", e);
        }
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasAuthority('PAYMENT_REVIEW')")
    PageResponse<PaymentView> reviewQueue(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return payments.reviewQueue(PageQuery.of(page, size));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAYMENT_REVIEW')")
    PaymentView approve(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return payments.approve(id, request.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAYMENT_REVIEW')")
    PaymentView reject(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return payments.reject(id, request.note());
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('PAYMENT_REVERSE')")
    PaymentView reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest request) {
        return payments.reverse(id, new ReverseCommand(request.reason(), request.note()));
    }

    @GetMapping("/pending-reallocation")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    PageResponse<PaymentView> pendingReallocation(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return payments.pendingReallocation(PageQuery.of(page, size));
    }

    @PostMapping("/{id}/reallocations")
    @PreAuthorize("hasAuthority('RECONCILIATION_MANAGE')")
    PaymentView reallocate(@PathVariable UUID id, @Valid @RequestBody ReallocateRequest request) {
        return payments.reallocate(id, new ReallocateCommand(request.sourceAllocationId(), request.amount(),
                request.target() == null ? null : request.target().input(), request.refundReference(), request.note()));
    }

    /** Download sem cache (comprovante pode conter dados pessoais); registra FILE_DOWNLOADED. */
    @GetMapping("/{id}/evidence")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    ResponseEntity<InputStreamResource> evidence(@PathVariable UUID id) {
        EvidenceContent file = payments.openEvidence(id);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName() == null ? "comprovante" : file.fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()));
        if (file.size() >= 0) {
            builder.contentLength(file.size());
        }
        return builder.body(new InputStreamResource(file.content()));
    }

    record TargetRequest(@NotBlank @Size(max = 20) String kind, UUID agreementId, @Size(max = 200) List<UUID> itemIds,
                         @Size(max = 20) String strategy) {

        TargetInput input() {
            return new TargetInput(kind, agreementId, itemIds, strategy);
        }
    }

    record ExternalRequest(@NotNull UUID creditorId, @NotNull UUID debtorId,
                           @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                           @NotNull Instant paidAt, @NotBlank @Size(max = 20) String method,
                           @NotBlank @Size(max = 30) String origin, @Size(max = 100) String externalReference,
                           @Size(max = 1000) String note, @NotNull @Valid TargetRequest target) {
    }

    record NoteRequest(@Size(max = 1000) String note) {
    }

    record ReverseRequest(@NotBlank @Size(max = 30) String reason, @NotBlank @Size(max = 1000) String note) {
    }

    record ReallocateRequest(UUID sourceAllocationId,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                             @Valid TargetRequest target, @Size(max = 100) String refundReference,
                             @NotBlank @Size(max = 1000) String note) {
    }
}
