package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.Evidence;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.ReportCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.ReportedPayment;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.TitleMatch;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.storage.MalwareScanner;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Pagamento direto informado pela pessoa do credor (ADR 0011, fatia 6). Só o ator {@code CREDITOR}; permissão e credor
 * conferidos no caso de uso. {@code Idempotency-Key} obrigatória (RS-35); o envio conta no limite por pessoa antes de
 * qualquer checagem; verificador de malware fora do ar → 503 com {@code Retry-After} (nada é gravado).
 */
@RestController
@RequestMapping(ApiPaths.CREDITOR + "/payments")
@PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
class CreditorPaymentController {

    private static final java.util.regex.Pattern IDEMPOTENCY_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{8,80}");

    private final CreditorPaymentPortalUseCases payments;
    private final CreditorUploadLimiter limiter;

    CreditorPaymentController(CreditorPaymentPortalUseCases payments, CreditorUploadLimiter limiter) {
        this.payments = payments;
        this.limiter = limiter;
    }

    @GetMapping
    List<ReportedPayment> list() {
        return payments.reported();
    }

    /** Busca exata pelo número do título no credor (termo fora da URL de navegação; parâmetro só nesta chamada). */
    @GetMapping("/titles")
    List<TitleMatch> titles(@RequestParam @Size(max = 60) String number, HttpServletResponse http) {
        limiter.acquire("payment_title_search", CreditorUploadLimiter.SEARCH_LIMIT_PER_MINUTE, http);
        return payments.findTitles(number);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ReportedPayment report(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                           @Valid @RequestPart("report") ReportRequest request,
                           @RequestPart(name = "evidence", required = false) MultipartFile evidence,
                           HttpServletResponse http) {
        limiter.acquire("payment_report", http);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            // antes da verificação do código: chave inválida nunca registra MFA_SUCCESS de uma ação que não acontece
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key obrigatória (8 a 80 caracteres)");
        }
        if (evidence == null || evidence.isEmpty()) {
            throw new BusinessRuleException("payment.evidence_required", "Anexe o comprovante do pagamento");
        }
        if (evidence.getSize() > CreditorDisputeController.MAX_DOCUMENT_BYTES) {
            throw new BusinessRuleException("payment.evidence_too_large", "O comprovante pode ter até 10 MB");
        }
        try {
            return payments.report(new ReportCommand(request.titleNumbers(), request.amount(), request.paidOn(),
                            request.method(), request.reference(), request.note(), request.code()),
                    new Evidence(evidence.getOriginalFilename(), evidence.getContentType(), evidence.getSize(),
                            evidence.getInputStream()), idempotencyKey);
        } catch (MalwareScanner.MalwareScanUnavailableException e) {
            http.setHeader(HttpHeaders.RETRY_AFTER, "60");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A verificação de segurança do comprovante está indisponível agora. Tente de novo em alguns minutos.");
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o comprovante enviado", e);
        }
    }

    record ReportRequest(@NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 60) String> titleNumbers,
                         @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
                         @NotNull LocalDate paidOn,
                         @NotBlank @Pattern(regexp = "PIX|BOLETO|CARD|TRANSFER|CASH|OTHER") String method,
                         @Size(max = 100) String reference, @Size(max = 1000) String note,
                         @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }
}
