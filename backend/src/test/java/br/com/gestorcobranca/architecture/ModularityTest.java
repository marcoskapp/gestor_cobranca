package br.com.gestorcobranca.architecture;

import br.com.gestorcobranca.GestorCobrancaApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifica as fronteiras entre módulos: sem ciclos, somente dependências declaradas em
 * {@code allowedDependencies} e somente pela interface pública ({@code usecases}).
 * Também gera a documentação dos módulos em {@code target/spring-modulith-docs}.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(GestorCobrancaApplication.class);

    @Test
    void modulesRespectDeclaredBoundaries() {
        modules.verify();
    }

    @Test
    void writeModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
