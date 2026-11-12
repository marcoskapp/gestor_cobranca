package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.DebtorInsolvency;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.ProceedingView;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.RegisterProceedingCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Recuperação judicial, recuperação extrajudicial e falência do devedor PJ (cobrança B2B §7). A consulta aparece na
 * ficha do devedor e na ficha do caso (quem atende o caso precisa saber por que ele está suspenso); registrar, convolar
 * e encerrar exigem {@code DEBTOR_MANAGE}.
 */
@RestController
@RequestMapping(ApiPaths.V1)
class InsolvencyController {

    private final InsolvencyUseCases insolvency;

    InsolvencyController(InsolvencyUseCases insolvency) {
        this.insolvency = insolvency;
    }

    @GetMapping("/debtors/{debtorId}/insolvency")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    DebtorInsolvency ofDebtor(@PathVariable UUID debtorId) {
        return insolvency.ofDebtor(debtorId);
    }

    @PostMapping("/debtors/{debtorId}/insolvency")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ProceedingView register(@PathVariable UUID debtorId, @Valid @RequestBody RegisterRequest request) {
        return insolvency.register(new RegisterProceedingCommand(debtorId, request.kind(), request.caseNumber(),
                request.court(), request.filedOn(), request.note()));
    }

    @PostMapping("/insolvency-proceedings/{id}/bankruptcy")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ProceedingView convertToBankruptcy(@PathVariable UUID id, @Valid @RequestBody ConvertRequest request) {
        return insolvency.convertToBankruptcy(id, request.convertedOn(), request.reason());
    }

    @PostMapping("/insolvency-proceedings/{id}/end")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ProceedingView end(@PathVariable UUID id, @Valid @RequestBody EndRequest request) {
        return insolvency.end(id, request.endedOn(), request.reason());
    }

    record RegisterRequest(@NotBlank @Pattern(regexp = "JUDICIAL_RECOVERY|EXTRAJUDICIAL_RECOVERY|BANKRUPTCY") String kind,
                           @NotBlank @Size(max = 30) String caseNumber, @Size(max = 150) String court,
                           @NotNull LocalDate filedOn, @NotBlank @Size(max = 500) String note) {
    }

    record ConvertRequest(@NotNull LocalDate convertedOn, @NotBlank @Size(max = 500) String reason) {
    }

    record EndRequest(@NotNull LocalDate endedOn, @NotBlank @Size(max = 500) String reason) {
    }
}
