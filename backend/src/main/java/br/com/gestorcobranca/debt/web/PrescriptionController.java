package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases.RuleCommand;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases.RuleView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceDownload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.PrescriptionView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.RegisterInterruptionCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prescrição: consulta por título, registro de interrupções (com evidência opcional) e regras
 * do tenant por natureza do título e pretensão.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/debt")
class PrescriptionController {

    private final TitlePrescriptionUseCases prescriptions;
    private final PrescriptionRuleUseCases rules;

    PrescriptionController(TitlePrescriptionUseCases prescriptions, PrescriptionRuleUseCases rules) {
        this.prescriptions = prescriptions;
        this.rules = rules;
    }

    // ---------------------------------------------------------------- título

    @GetMapping("/titles/{titleId}/prescription")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    PrescriptionView get(@PathVariable UUID titleId) {
        return prescriptions.get(titleId);
    }

    @PostMapping(path = "/titles/{titleId}/prescription/interruptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRESCRIPTION_MANAGE')")
    PrescriptionView registerInterruption(@PathVariable UUID titleId, @Valid @RequestBody InterruptionRequest request) {
        return prescriptions.registerInterruption(titleId, request.toCommand(), null);
    }

    /** Interrupção com evidência: partes {@code interruption} (JSON) e {@code file} (PDF, PNG ou JPEG). */
    @PostMapping(path = "/titles/{titleId}/prescription/interruptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRESCRIPTION_MANAGE')")
    PrescriptionView registerInterruptionWithEvidence(@PathVariable UUID titleId,
                                                      @Valid @RequestPart("interruption") InterruptionRequest request,
                                                      @RequestPart("file") MultipartFile file) {
        try {
            return prescriptions.registerInterruption(titleId, request.toCommand(),
                    new EvidenceUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(),
                            file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    @GetMapping("/titles/{titleId}/prescription/interruptions/{interruptionId}/evidence")
    @PreAuthorize("hasAuthority('PRESCRIPTION_MANAGE')")
    ResponseEntity<InputStreamResource> evidence(@PathVariable UUID titleId, @PathVariable UUID interruptionId) {
        EvidenceDownload file = prescriptions.evidence(titleId, interruptionId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()));
        if (file.size() >= 0) {
            builder.contentLength(file.size());
        }
        return builder.body(new InputStreamResource(file.content()));
    }

    // ---------------------------------------------------------------- regras

    @GetMapping("/prescription-rules")
    @PreAuthorize("hasAuthority('POLICY_VIEW') or hasAuthority('PRESCRIPTION_MANAGE')")
    List<RuleView> rules() {
        return rules.list();
    }

    @GetMapping("/prescription-rules/{ruleId}")
    @PreAuthorize("hasAuthority('POLICY_VIEW') or hasAuthority('PRESCRIPTION_MANAGE')")
    RuleView rule(@PathVariable UUID ruleId) {
        return rules.get(ruleId);
    }

    @PostMapping("/prescription-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    RuleView createRule(@Valid @RequestBody RuleRequest request) {
        return rules.create(request.toCommand());
    }

    @PutMapping("/prescription-rules/{ruleId}")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    RuleView updateRule(@PathVariable UUID ruleId, @Valid @RequestBody RuleRequest request) {
        return rules.update(ruleId, request.toCommand());
    }

    @PostMapping("/prescription-rules/{ruleId}/validate")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    RuleView validateRule(@PathVariable UUID ruleId) {
        return rules.validate(ruleId);
    }

    @DeleteMapping("/prescription-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    void deleteRule(@PathVariable UUID ruleId, @RequestParam @NotBlank @Size(max = 500) String reason) {
        rules.delete(ruleId, reason);
    }

    /** Copia da tabela de mercado as regras ainda inexistentes (pendentes de validação jurídica). */
    @PostMapping("/prescription-rules/apply-market-defaults")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    List<RuleView> applyMarketDefaults() {
        return rules.applyMarketDefaults();
    }

    record InterruptionRequest(@NotBlank @Size(max = 20) String type,
                               @NotNull @PastOrPresent LocalDate date,
                               @Size(max = 200) String reference,
                               @Size(max = 500) String notes) {

        RegisterInterruptionCommand toCommand() {
            return new RegisterInterruptionCommand(type, date, reference, notes);
        }
    }

    /** Na alteração, {@code instrumentType} e {@code claim} são ignorados (imutáveis). */
    record RuleRequest(@NotBlank @Size(max = 30) String instrumentType,
                       @NotBlank @Size(max = 20) String claim,
                       @Min(1) @Max(360) int termMonths,
                       @NotBlank @Size(max = 30) String base,
                       @Min(0) @Max(365) int startOffsetDays,
                       @NotBlank @Size(max = 20) String interruptionPolicy,
                       @Min(1) @Max(730) int alertDays,
                       @Size(max = 300) String legalBasis) {

        RuleCommand toCommand() {
            return new RuleCommand(instrumentType, claim, termMonths, base, startOffsetDays, interruptionPolicy, alertDays,
                    legalBasis);
        }
    }
}
