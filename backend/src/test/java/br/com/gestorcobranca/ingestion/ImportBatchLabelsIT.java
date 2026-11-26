package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.BatchLabel;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rótulo dos lotes para a aba "Pela carga do credor" (H07 parte 2b, SEG-01): {@code ImportBatchUseCases#labels} e
 * {@code GET /api/v1/ingestion/batches/labels} só devolvem os lotes visíveis para quem pede (credor fora do escopo,
 * outra assessoria e id inexistente ficam de fora, sem erro), nunca o nome do arquivo, e recusam mais de 100 ids.
 */
class ImportBatchLabelsIT extends IngestionTestSupport {

    private static final String SECRET_FILE_NAME = "carteira-segredo-do-credor.csv";

    @Autowired
    WebApplicationContext webApplicationContext;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private Actor viewerOf(UUID tenantId, CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, Ids.newId(), tenantId, Ids.newId(), "PERSONALIZADO",
                EnumSet.of(Permission.IMPORT_VIEW, Permission.PAYMENT_VIEW), scope);
    }

    private ImportBatchView uploadFor(UUID creditorId, UUID portfolioId) {
        byte[] bytes = (HEADER + "\n" + line("529.982.247-25", "Maria Silva", "L-" + creditorId.toString()
                .substring(0, 4), "100,00", null) + "\n").getBytes(StandardCharsets.UTF_8);
        return asImporterOf(creditorId, () -> batches.uploadFile(new UploadFileCommand(portfolioId, null, null, false,
                null, SECRET_FILE_NAME, "text/csv", bytes.length, () -> new java.io.ByteArrayInputStream(bytes))));
    }

    private MockHttpServletRequestBuilder labels(Actor actor, UUID... ids) {
        MockHttpServletRequestBuilder request = get("/api/v1/ingestion/batches/labels")
                .with(authentication(ActorAuthentication.of(actor)));
        for (UUID id : ids) {
            request.param("ids", id.toString());
        }
        return request;
    }

    @Test
    void onlyVisibleBatchesNeverTheFileNameAndAtMostOneHundredIds() throws Exception {
        ImportBatchView batchA = uploadFor(creditorA, portfolioA);
        ImportBatchView batchB = uploadFor(creditorB, portfolioB);
        UUID unknown = Ids.newId();
        MockMvc mvc = mvc();

        // quem vê os dois credores recebe os dois rótulos; o id inexistente fica de fora, sem erro
        Actor all = viewerOf(tenant, CreditorScope.ALL);
        List<BatchLabel> both = tenantExecution.callAs(all, () -> batches.labels(List.of(batchA.id(), batchB.id(),
                unknown)));
        assertThat(both).extracting(BatchLabel::id).containsExactlyInAnyOrder(batchA.id(), batchB.id());
        assertThat(both).allSatisfy(l -> {
            assertThat(l.createdAt()).isNotNull();
            assertThat(l.layoutCode()).isNotBlank();
        });

        // usuário só do credor A: o lote do B fica de fora, sem erro e sem rótulo
        Actor onlyA = viewerOf(tenant, CreditorScope.of(List.of(creditorA)));
        assertThat(tenantExecution.callAs(onlyA, () -> batches.labels(List.of(batchA.id(), batchB.id()))))
                .extracting(BatchLabel::id).containsExactly(batchA.id());
        String body = mvc.perform(labels(onlyA, batchA.id(), batchB.id(), unknown)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(batchA.id().toString()).doesNotContain(batchB.id().toString())
                .doesNotContain(unknown.toString()).doesNotContain(SECRET_FILE_NAME).doesNotContain("segredo")
                .doesNotContain("fileName");

        // usuário só do credor B: o contrário
        Actor onlyB = viewerOf(tenant, CreditorScope.of(List.of(creditorB)));
        assertThat(tenantExecution.callAs(onlyB, () -> batches.labels(List.of(batchA.id(), batchB.id()))))
                .extracting(BatchLabel::id).containsExactly(batchB.id());

        // sem IMPORT_VIEW: recusado
        Actor noImport = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                EnumSet.of(Permission.PAYMENT_VIEW), CreditorScope.ALL);
        assertThatThrownBy(() -> tenantExecution.callAs(noImport, () -> batches.labels(List.of(batchA.id()))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        mvc.perform(labels(noImport, batchA.id())).andExpect(status().isForbidden());

        // mais de 100 ids: recusado (422), com 100 responde
        UUID[] tooMany = IntStream.range(0, 101).mapToObj(i -> Ids.newId()).toArray(UUID[]::new);
        assertThatThrownBy(() -> tenantExecution.callAs(all, () -> batches.labels(List.of(tooMany))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.labels_limit");
        mvc.perform(labels(all, tooMany)).andExpect(status().isUnprocessableEntity());
        mvc.perform(labels(all, java.util.Arrays.copyOf(tooMany, 100))).andExpect(status().isOk());

        // outra assessoria não vê nenhum lote desta, mesmo com todos os credores
        UUID otherTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand("ing-outra-" + UUID.randomUUID().toString().substring(0, 6),
                        "Outra Assessoria Ltda", "Outra", "11.444.777/0001-61")).id());
        Actor foreign = viewerOf(otherTenant, CreditorScope.ALL);
        assertThat(tenantExecution.callAs(foreign, () -> batches.labels(List.of(batchA.id(), batchB.id())))).isEmpty();
        String foreignBody = mvc.perform(labels(foreign, batchA.id(), batchB.id())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(foreignBody).isEqualTo("[]");
    }
}
