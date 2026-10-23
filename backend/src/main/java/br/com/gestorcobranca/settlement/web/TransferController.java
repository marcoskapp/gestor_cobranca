package br.com.gestorcobranca.settlement.web;

import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.BalanceView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.EvidenceContent;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.EvidenceUpload;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.TransferCommand;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.TransferView;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Repasses ao credor com comprovante e saldo a repassar por credor. */
@RestController
@RequestMapping(ApiPaths.V1 + "/settlement")
class TransferController {

    private final TransferUseCases transfers;

    TransferController(TransferUseCases transfers) {
        this.transfers = transfers;
    }

    @PostMapping(path = "/transfers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SETTLEMENT_TRANSFER')")
    TransferView register(@Valid @RequestPart("transfer") TransferRequest request,
                          @RequestPart("evidence") MultipartFile evidence,
                          @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            return transfers.register(new TransferCommand(request.statementId(), request.amount(),
                    request.transferredOn(), request.bankReference(), request.note()),
                    new EvidenceUpload(evidence.getOriginalFilename(), evidence.getContentType(), evidence.getSize(),
                            evidence.getInputStream()), idempotencyKey);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o comprovante enviado", e);
        }
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasAnyAuthority('SETTLEMENT_VIEW', 'SETTLEMENT_TRANSFER')")
    PageResponse<TransferView> search(@RequestParam(required = false) UUID creditorId,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return transfers.search(creditorId, PageQuery.of(page, size));
    }

    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAnyAuthority('SETTLEMENT_VIEW', 'SETTLEMENT_TRANSFER')")
    TransferView get(@PathVariable UUID id) {
        return transfers.get(id);
    }

    @PostMapping("/transfers/{id}/cancel")
    @PreAuthorize("hasAuthority('SETTLEMENT_TRANSFER')")
    TransferView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return transfers.cancel(id, request.reason());
    }

    @GetMapping("/transfers/{id}/evidence")
    @PreAuthorize("hasAnyAuthority('SETTLEMENT_VIEW', 'SETTLEMENT_TRANSFER')")
    ResponseEntity<InputStreamResource> evidence(@PathVariable UUID id) {
        EvidenceContent file = transfers.openEvidence(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @GetMapping("/balances")
    @PreAuthorize("hasAnyAuthority('SETTLEMENT_VIEW', 'SETTLEMENT_TRANSFER')")
    List<BalanceView> balances(@RequestParam(required = false) UUID creditorId) {
        return transfers.balances(creditorId);
    }

    record TransferRequest(@NotNull UUID statementId,
                           @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                           @NotNull LocalDate transferredOn, @NotBlank @Size(max = 120) String bankReference,
                           @Size(max = 1000) String note) {
    }

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
