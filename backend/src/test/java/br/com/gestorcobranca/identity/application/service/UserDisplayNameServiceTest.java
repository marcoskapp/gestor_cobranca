package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDisplayNames;
import br.com.gestorcobranca.identity.application.port.in.UserDisplayNames.UserName;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDisplayNameServiceTest {

    private final User ana = new User("Ana Operadora", "ana@exemplo.com", null);
    private final User bruno = new User("Bruno Gestor", "bruno@exemplo.com", null);
    private final List<Collection<UUID>> queried = new ArrayList<>();
    private final UserDisplayNameService service = new UserDisplayNameService(repository(List.of(ana, bruno)));

    @Test
    void returnsOnlyIdAndNameOfExistingUsersSortedByName() {
        List<UserName> names = service.resolve(List.of(bruno.getId(), ana.getId(), bruno.getId(), UUID.randomUUID()));

        assertThat(names).containsExactly(new UserName(ana.getId(), "Ana Operadora"),
                new UserName(bruno.getId(), "Bruno Gestor"));
        assertThat(queried).singleElement().satisfies(ids -> assertThat(ids).hasSize(3));
        assertThat(UserName.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly("id", "name");
    }

    @Test
    void emptyInputDoesNotQuery() {
        assertThat(service.resolve(List.of())).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
        assertThat(queried).isEmpty();
    }

    @Test
    void rejectsMoreThanTheLimit() {
        List<UUID> ids = IntStream.range(0, UserDisplayNames.MAX_IDS + 1).mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> service.resolve(ids)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo("user.names_limit"));
        assertThat(service.resolve(ids.subList(0, UserDisplayNames.MAX_IDS))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private UserRepository repository(List<User> users) {
        return (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("findAllByIdIn")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Collection<UUID> ids = (Collection<UUID>) args[0];
                    queried.add(ids);
                    return users.stream().filter(u -> ids.contains(u.getId())).toList();
                });
    }
}
