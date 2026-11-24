package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.CreditorPortalView;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.CreditorUserView;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.InviteCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Portal do credor na ficha do credor (assessoria): liberar e gerenciar as pessoas do credor (ADR 0011). */
@RestController
@RequestMapping(ApiPaths.V1 + "/creditors/{creditorId}/portal")
@PreAuthorize("hasAuthority('CREDITOR_PORTAL_MANAGE')")
class CreditorPortalAdminController {

    private final CreditorPortalAdministration administration;

    CreditorPortalAdminController(CreditorPortalAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    CreditorPortalView status(@PathVariable UUID creditorId) {
        return administration.status(creditorId);
    }

    @PostMapping("/enable")
    CreditorPortalView enable(@PathVariable UUID creditorId) {
        return administration.enable(creditorId);
    }

    @PostMapping("/disable")
    CreditorPortalView disable(@PathVariable UUID creditorId) {
        return administration.disable(creditorId);
    }

    @PostMapping("/users")
    CreditorUserView invite(@PathVariable UUID creditorId, @Valid @RequestBody InviteRequest request) {
        return administration.invite(creditorId, new InviteCommand(request.name(), request.email(), request.role()));
    }

    @PostMapping("/users/{userId}/resend-invitation")
    CreditorUserView resendInvitation(@PathVariable UUID creditorId, @PathVariable UUID userId) {
        return administration.resendInvitation(creditorId, userId);
    }

    @PostMapping("/users/{userId}/reset-access")
    CreditorUserView resetAccess(@PathVariable UUID creditorId, @PathVariable UUID userId) {
        return administration.resetAccess(creditorId, userId);
    }

    @PutMapping("/users/{userId}/role")
    CreditorUserView changeRole(@PathVariable UUID creditorId, @PathVariable UUID userId,
                                @Valid @RequestBody RoleRequest request) {
        return administration.changeRole(creditorId, userId, request.role());
    }

    @PostMapping("/users/{userId}/revoke")
    CreditorUserView revoke(@PathVariable UUID creditorId, @PathVariable UUID userId,
                            @Valid @RequestBody RevokeRequest request) {
        return administration.revoke(creditorId, userId, request.reason());
    }

    record InviteRequest(@NotBlank @Size(max = 150) String name, @NotBlank @Size(max = 200) String email,
                         @NotBlank @Size(max = 20) String role) {
    }

    record RoleRequest(@NotBlank @Size(max = 20) String role) {
    }

    record RevokeRequest(@NotBlank @Size(max = 200) String reason) {
    }
}
