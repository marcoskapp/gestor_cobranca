package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.AssignCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.AssignmentView;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.EffectiveAuthorityView;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.ProfileCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.ProfileView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Perfis de alçada (humanos e assistente) e sua atribuição por usuário/credor. */
@RestController
@RequestMapping(ApiPaths.V1 + "/negotiation")
@Tag(name = "Negotiation — alçadas", description = "Perfis de alçada e atribuição")
class AuthorityController {

    private final AuthorityUseCases authority;

    AuthorityController(AuthorityUseCases authority) {
        this.authority = authority;
    }

    @GetMapping("/authority-profiles")
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    List<ProfileView> profiles() {
        return authority.listProfiles();
    }

    @GetMapping("/authority-profiles/{id}")
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    ProfileView profile(@PathVariable UUID id) {
        return authority.getProfile(id);
    }

    @PostMapping("/authority-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    ProfileView create(@Valid @RequestBody ProfileRequest request) {
        return authority.createProfile(request.toCommand());
    }

    @PutMapping("/authority-profiles/{id}")
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    @Operation(summary = "Ajusta os parâmetros do perfil (o código é imutável)")
    ProfileView update(@PathVariable UUID id, @Valid @RequestBody ProfileRequest request) {
        return authority.updateProfile(id, request.toCommand());
    }

    @GetMapping("/authority-assignments")
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    List<AssignmentView> assignments(@RequestParam(required = false) String subjectType,
                                     @RequestParam(required = false) UUID subjectId) {
        return authority.assignments(subjectType, subjectId);
    }

    @PostMapping("/authority-assignments")
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    @Operation(summary = "Atribui (ou troca) o perfil de um usuário ou política do assistente, geral ou por credor")
    AssignmentView assign(@Valid @RequestBody AssignRequest request) {
        return authority.assign(new AssignCommand(request.subjectType(), request.subjectId(), request.creditorId(),
                request.profileId()));
    }

    @DeleteMapping("/authority-assignments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    void unassign(@PathVariable UUID id) {
        authority.unassign(id);
    }

    @GetMapping("/authority/effective")
    @PreAuthorize("hasAuthority('AUTHORITY_MANAGE')")
    EffectiveAuthorityView effective(@RequestParam String subjectType, @RequestParam UUID subjectId,
                                     @RequestParam UUID creditorId) {
        return authority.effective(subjectType, subjectId, creditorId);
    }

    @GetMapping("/authority/me")
    @PreAuthorize("hasAuthority('NEGOTIATION_SIMULATE')")
    @Operation(summary = "Alçada efetiva do usuário autenticado para o credor")
    EffectiveAuthorityView mine(@RequestParam UUID creditorId) {
        return authority.mine(creditorId);
    }

    record ProfileRequest(@Pattern(regexp = "[A-Z0-9_]{2,40}") String code,
                          @NotBlank @Size(max = 120) String name,
                          @Size(max = 500) String description,
                          @Min(0) @Max(99) int maxConcessionLevel,
                          @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal maxDiscountShare,
                          @Min(1) @Max(360) int maxInstallments,
                          @DecimalMin(value = "0", inclusive = false) BigDecimal maxAgreementAmount,
                          @NotNull @DecimalMin("0") @DecimalMax(value = "100", inclusive = false) BigDecimal minDownPaymentShare,
                          boolean canFormalize, boolean canApprove,
                          @Min(0) @Max(10) int maxRenegotiations,
                          @Size(max = 200) Set<UUID> creditorIds) {

        ProfileCommand toCommand() {
            return new ProfileCommand(code, name, description, maxConcessionLevel, maxDiscountShare, maxInstallments,
                    maxAgreementAmount, minDownPaymentShare, canFormalize, canApprove, maxRenegotiations,
                    creditorIds == null ? Set.of() : creditorIds);
        }
    }

    record AssignRequest(@NotBlank @Pattern(regexp = "USER|ASSISTANT_POLICY") String subjectType,
                         @NotNull UUID subjectId, UUID creditorId, @NotNull UUID profileId) {
    }
}
