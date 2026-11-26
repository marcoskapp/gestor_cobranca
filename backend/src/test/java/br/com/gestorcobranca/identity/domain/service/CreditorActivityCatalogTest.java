package br.com.gestorcobranca.identity.domain.service;

import br.com.gestorcobranca.identity.domain.service.CreditorActivityCatalog.Activity;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lista branca de "Minha atividade" do portal do credor e o cursor opaco (fatia 7; RS-47 a RS-49). */
class CreditorActivityCatalogTest {

    /** Caminhos comuns por onde passam as ações da pessoa do credor (resposta à contestação, informe de pagamento). */
    private static final Set<String> CREDITOR_PATHS = Set.of("DisputeCreditorChannel.java", "PaymentRecorder.java");
    private static final Pattern AUDIT_ACTION = Pattern.compile("AuditEntry\\.of\\(\"([a-z0-9_.]+)\"");

    @Test
    void soAsAcoesDaListaBrancaAparecemComCodigoCurto() {
        assertThat(CreditorActivityCatalog.audit("settlement_statement.acknowledged")).contains(Activity.STATEMENT_ACKNOWLEDGED);
        assertThat(CreditorActivityCatalog.audit("dispute.creditor_responded")).contains(Activity.DISPUTE_RESPONDED);
        assertThat(CreditorActivityCatalog.audit("payment.creditor_portal_reported")).contains(Activity.PAYMENT_REPORTED);
        // o mesmo informe também grava payment.informed: fica fora para não aparecer duas vezes
        assertThat(CreditorActivityCatalog.audit("payment.informed")).isEmpty();
        assertThat(CreditorActivityCatalog.audit("creditor_user.revoked")).isEmpty();
        assertThat(CreditorActivityCatalog.audit("qualquer.outra")).isEmpty();
        assertThat(CreditorActivityCatalog.access(AccessEventType.LOGIN_SUCCESS)).contains(Activity.SIGNED_IN);
        assertThat(CreditorActivityCatalog.access(AccessEventType.MFA_FAILURE)).isEmpty();
        assertThat(CreditorActivityCatalog.reference("settlement_statement")).contains("STATEMENT");
        assertThat(CreditorActivityCatalog.reference("debtor")).isEmpty();
        assertThat(CreditorActivityCatalog.reference(null)).isEmpty();
    }

    /** Armadilha: todo tipo do log de acesso tem decisão explícita — incluído ou excluído, nunca os dois. */
    @Test
    void todoTipoDoLogDeAcessoTemDecisaoExplicita() {
        Set<AccessEventType> decided = EnumSet.noneOf(AccessEventType.class);
        decided.addAll(CreditorActivityCatalog.accessTypes());
        decided.addAll(CreditorActivityCatalog.excludedAccessTypes());
        assertThat(EnumSet.complementOf(EnumSet.copyOf(decided)))
                .as("tipo novo do log de acesso sem decisão para \"Minha atividade\" (CreditorActivityCatalog)").isEmpty();
        assertThat(CreditorActivityCatalog.accessTypes()).doesNotContainAnyElementsOf(
                CreditorActivityCatalog.excludedAccessTypes());
    }

    /**
     * Armadilha: toda ação de auditoria que cita o credor ({@code creditor} no nome) ou que é gravada por um serviço do
     * credor ({@code Creditor*.java}) ou pelos caminhos comuns que ele usa (canal de resposta da contestação, registro do
     * pagamento) tem decisão explícita no catálogo.
     */
    @Test
    void todaAcaoDeAuditoriaDoCredorTemDecisaoExplicita() throws IOException {
        Set<String> found = new TreeSet<>();
        Path root = Path.of("src/main/java/br/com/gestorcobranca");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                boolean creditorService = name.startsWith("Creditor") || CREDITOR_PATHS.contains(name);
                Matcher m = AUDIT_ACTION.matcher(Files.readString(file));
                while (m.find()) {
                    if (creditorService || m.group(1).contains("creditor")) {
                        found.add(m.group(1));
                    }
                }
            }
        }
        assertThat(found).as("a varredura achou as ações do portal").contains("settlement_statement.acknowledged",
                "dispute.creditor_responded", "payment.creditor_portal_reported", "payment.informed");
        Set<String> decided = new TreeSet<>(CreditorActivityCatalog.auditActions());
        decided.addAll(CreditorActivityCatalog.excludedAuditActions());
        assertThat(found).as("ação de auditoria do credor sem decisão para \"Minha atividade\" (CreditorActivityCatalog)")
                .isSubsetOf(decided);
        assertThat(CreditorActivityCatalog.auditActions()).doesNotContainAnyElementsOf(
                CreditorActivityCatalog.excludedAuditActions());
    }

    @Test
    void cursorOpacoIdaEVoltaEInvalidoRecusado() {
        Instant at = Instant.parse("2026-11-26T12:34:56.123456789Z");
        UUID id = UUID.randomUUID();
        String encoded = new CreditorActivityCursor(at, id).encode();
        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain(id.toString());
        CreditorActivityCursor decoded = CreditorActivityCursor.decode(encoded);
        assertThat(decoded.occurredAt()).isEqualTo(Instant.parse("2026-11-26T12:34:56.123456Z"));
        assertThat(decoded.id()).isEqualTo(id);
        for (String bad : new String[]{"", "x", "bm9wZQ", "YTJ8MXx4", "a".repeat(200), "YTF8eHx5"}) {
            assertThatThrownBy(() -> CreditorActivityCursor.decode(bad)).isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("code", "creditor_activity.cursor_invalid");
        }
    }
}
