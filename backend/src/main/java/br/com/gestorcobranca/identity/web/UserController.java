package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.SessionUseCases;
import br.com.gestorcobranca.identity.application.port.in.SessionUseCases.SessionView;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.UpdateUserCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.UserView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/users")
class UserController {

    private final UserManagementUseCases users;
    private final SessionUseCases sessions;

    UserController(UserManagementUseCases users, SessionUseCases sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    PageResponse<UserView> search(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                  @RequestParam(required = false) UUID roleId,
                                  @RequestParam(required = false) UUID businessUnitId,
                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return users.search(q, status, roleId, businessUnitId, PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    UserView get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(new CreateUserCommand(request.name(), request.email(), request.businessUnitId(),
                request.roleIds(), request.allCreditors(), request.creditorIds()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, new UpdateUserCommand(request.name(), request.businessUnitId()));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView assignRoles(@PathVariable UUID id, @Valid @RequestBody RolesRequest request) {
        return users.assignRoles(id, request.roleIds());
    }

    @PutMapping("/{id}/creditor-scope")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView defineScope(@PathVariable UUID id, @Valid @RequestBody ScopeRequest request) {
        return users.defineCreditorScope(id, request.allCreditors(), request.creditorIds());
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView disable(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return users.disable(id, request.reason());
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView enable(@PathVariable UUID id) {
        return users.enable(id);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView unlock(@PathVariable UUID id) {
        return users.unlock(id);
    }

    @PostMapping("/{id}/mfa/reset")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView resetMfa(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return users.resetMfa(id, request.reason());
    }

    @PostMapping("/{id}/credential-link")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    void sendCredentialLink(@PathVariable UUID id) {
        users.sendCredentialLink(id);
    }

    @GetMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    List<SessionView> sessions(@PathVariable UUID id) {
        return sessions.sessionsOf(id);
    }

    @PostMapping("/{id}/sessions/revoke")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    int revokeSessions(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return sessions.revokeAllOf(id, request.reason());
    }

    record CreateUserRequest(@NotBlank @Size(max = 150) String name, @NotBlank @Email @Size(max = 200) String email,
                             UUID businessUnitId, @NotEmpty Set<UUID> roleIds, boolean allCreditors,
                             Set<UUID> creditorIds) {
    }

    record UpdateUserRequest(@NotBlank @Size(max = 150) String name, UUID businessUnitId) {
    }

    record RolesRequest(@NotEmpty Set<UUID> roleIds) {
    }

    record ScopeRequest(boolean allCreditors, Set<UUID> creditorIds) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
