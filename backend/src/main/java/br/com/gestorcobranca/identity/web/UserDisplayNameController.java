package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.UserDisplayNames;
import br.com.gestorcobranca.identity.application.port.in.UserDisplayNames.UserName;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Nomes de exibição de usuários para quem opera ou consulta (histórico de interações, autor de
 * política, evidências). Separado de {@code /users} (que exige {@code USER_VIEW} e devolve e-mail,
 * perfis e escopo): aqui só id e nome, até {@value UserDisplayNames#MAX_IDS} ids por chamada.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/users/display-names")
class UserDisplayNameController {

    private final UserDisplayNames names;

    UserDisplayNameController(UserDisplayNames names) {
        this.names = names;
    }

    /** @param ids ids separados por vírgula ou repetidos ({@code ?ids=a&ids=b}) */
    @GetMapping
    @PreAuthorize("hasAuthority('CASE_VIEW') or hasAuthority('POLICY_VIEW') or hasAuthority('USER_VIEW')")
    List<UserName> resolve(@RequestParam List<UUID> ids) {
        return names.resolve(ids);
    }
}
