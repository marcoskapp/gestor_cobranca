package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.publicapi.web.v1.BillingModels.SettlementPositionDto;
import br.com.gestorcobranca.publicapi.web.v1.BillingModels.SettlementStatementDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prestação de contas ao credor do token (módulo settlement): posição estimada do período em aberto e demonstrativos
 * já enviados ao credor — nunca rascunhos, cálculos em revisão ou aprovados ainda não enviados.
 */
@RestController
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/settlement")
@Tag(name = "Prestação de contas", description = "Repasse ao credor: posição do período e demonstrativos enviados")
class SettlementController {

    private final SettlementPositionUseCases settlement;
    private final PublicApiSupport support;

    SettlementController(SettlementPositionUseCases settlement, PublicApiSupport support) {
        this.settlement = settlement;
        this.support = support;
    }

    @GetMapping("/position")
    @PreAuthorize("hasAuthority('SCOPE_settlement:read')")
    @Operation(summary = "Repasse estimado do período em aberto (como o demonstrativo calcularia se fechado agora)")
    SettlementPositionDto position() {
        return SettlementPositionDto.of(settlement.position(support.creditorId()));
    }

    @GetMapping("/statements")
    @PreAuthorize("hasAuthority('SCOPE_settlement:read')")
    @Operation(summary = "Demonstrativos enviados ao credor (mais recentes primeiro)")
    ListResponse<SettlementStatementDto> statements() {
        return new ListResponse<>(settlement.sentToCreditor(support.creditorId()).stream()
                .map(SettlementStatementDto::of).toList(), null, false);
    }
}
