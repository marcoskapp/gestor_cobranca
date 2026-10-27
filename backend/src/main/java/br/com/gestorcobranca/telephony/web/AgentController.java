package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.AgentView;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.ConfigureAgentCommand;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.ConfigureEndpointCommand;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.EndpointView;
import br.com.gestorcobranca.telephony.application.port.in.SoftphoneUseCases;
import br.com.gestorcobranca.telephony.application.port.in.SoftphoneUseCases.SoftphoneSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operadores na telefonia (ramal, supervisão) e a presença do próprio operador para o discador
 * ({@code /presence/me}: entrar, disponível, pausa com motivo, campanha, sinal periódico, sair).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/telephony")
class AgentController {

    private final AgentUseCases agents;
    private final SoftphoneUseCases softphone;

    AgentController(AgentUseCases agents, SoftphoneUseCases softphone) {
        this.agents = agents;
        this.softphone = softphone;
    }

    @PutMapping("/agents/{userId}")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AgentView configure(@PathVariable UUID userId, @Valid @RequestBody ConfigureRequest request) {
        return agents.configure(userId, new ConfigureAgentCommand(request.extension(), request.active()));
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    List<AgentView> list() {
        return agents.list();
    }

    @GetMapping("/agents/endpoints")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    List<EndpointView> endpoints() {
        return agents.endpoints();
    }

    @PutMapping("/agents/{userId}/endpoints/{accountId}")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    EndpointView configureEndpoint(@PathVariable UUID userId, @PathVariable UUID accountId,
                                   @Valid @RequestBody EndpointRequest request) {
        return agents.configureEndpoint(userId, accountId, new ConfigureEndpointCommand(request.extension(),
                request.active()));
    }

    @DeleteMapping("/agents/{userId}/endpoints/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    void removeEndpoint(@PathVariable UUID userId, @PathVariable UUID accountId) {
        agents.removeEndpoint(userId, accountId);
    }

    @PostMapping("/agents/{userId}/logout")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    AgentView forceLogout(@PathVariable UUID userId, @Valid @RequestBody ReasonRequest request) {
        return agents.forceLogout(userId, request.reason());
    }

    @GetMapping("/presence/me")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    AgentView me() {
        return agents.me();
    }

    @PostMapping("/presence/me/login")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    AgentView login(@RequestBody(required = false) CampaignRequest request) {
        return agents.login(request == null ? null : request.campaignId());
    }

    @PostMapping("/presence/me/available")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    AgentView available() {
        return agents.available();
    }

    @PostMapping("/presence/me/pause")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    AgentView pause(@Valid @RequestBody PauseRequest request) {
        return agents.pause(request.reason());
    }

    @PostMapping("/presence/me/campaign")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    AgentView joinCampaign(@RequestBody CampaignRequest request) {
        return agents.joinCampaign(request.campaignId());
    }

    @PostMapping("/presence/me/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    void heartbeat() {
        agents.heartbeat();
    }

    @PostMapping("/presence/me/logout")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    AgentView logout() {
        return agents.logout();
    }

    /**
     * Tokens do softphone de navegador do próprio operador (identidade WebRTC do ramal em cada
     * plataforma). POST: emite credencial; resposta sem cache.
     */
    @PostMapping("/presence/me/softphone")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    ResponseEntity<List<SoftphoneSession>> softphone() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(softphone.sessions());
    }

    record ConfigureRequest(@NotBlank @Size(max = 64) String extension, boolean active) {
    }

    record CampaignRequest(UUID campaignId) {
    }

    record PauseRequest(@NotBlank @Size(max = 30) String reason) {
    }

    record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }

    record EndpointRequest(@Size(max = 64) String extension, boolean active) {
    }
}
