package br.com.gestorcobranca.settlement.web;

import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases.PositionView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Posição do repasse ao credor no período em aberto (simulação do demonstrativo, sem gravar). */
@RestController
@RequestMapping(ApiPaths.V1 + "/settlement/position")
class SettlementPositionController {

    private final SettlementPositionUseCases positions;

    SettlementPositionController(SettlementPositionUseCases positions) {
        this.positions = positions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SETTLEMENT_VIEW')")
    @Operation(summary = "Posição do período em aberto do credor, calculada agora como o demonstrativo calcularia")
    PositionView position(@RequestParam UUID creditorId) {
        return positions.position(creditorId);
    }
}
