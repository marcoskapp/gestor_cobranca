package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.ResponseTargetUseCases;
import br.com.gestorcobranca.communication.application.port.in.ResponseTargetUseCases.ResponseTargetView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Metas de primeira resposta (SLA) da caixa de entrada por canal. */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/response-targets")
class ResponseTargetController {

    private final ResponseTargetUseCases targets;

    ResponseTargetController(ResponseTargetUseCases targets) {
        this.targets = targets;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    List<ResponseTargetView> list() {
        return targets.list();
    }

    /** @param channel WHATSAPP, EMAIL ou SMS */
    @PutMapping("/{channel}")
    @PreAuthorize("hasAuthority('INBOX_MANAGE')")
    ResponseTargetView update(@PathVariable String channel, @Valid @RequestBody TargetRequest request) {
        return targets.update(channel, request.warningMinutes(), request.breachMinutes());
    }

    /** Minutos corridos (até 7 dias); o aviso precisa vir antes do limite (validado no domínio). */
    record TargetRequest(@Min(1) @Max(10_080) int warningMinutes, @Min(1) @Max(10_080) int breachMinutes) {
    }
}
