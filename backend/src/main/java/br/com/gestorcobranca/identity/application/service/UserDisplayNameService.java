package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDisplayNames;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class UserDisplayNameService implements UserDisplayNames {

    private final UserRepository users;

    UserDisplayNameService(UserRepository users) {
        this.users = users;
    }

    @Override
    public List<UserName> resolve(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = Set.copyOf(userIds);
        Guard.require(ids.size() <= MAX_IDS, "user.names_limit",
                "Informe no máximo %d usuários por consulta".formatted(MAX_IDS));
        // a tabela de usuários tem RLS por assessoria: ids de outra assessoria simplesmente não voltam
        return users.findAllByIdIn(ids).stream()
                .map(UserDisplayNameService::view)
                .sorted(Comparator.comparing(UserName::name))
                .toList();
    }

    private static UserName view(User user) {
        return new UserName(user.getId(), user.getName());
    }
}
