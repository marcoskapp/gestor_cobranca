package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pagamento recebido diretamente pelo credor informado pela integração do credor (H07, decisões 1466 e 1515–1518),
 * de ponta a ponta pela API pública: o token só com {@code payments:write} registra (antes era recusado), o canal
 * {@code INTEGRATION} fica gravado com o nome do token na tela, e a soma de todos os tokens do mesmo credor tem teto
 * próprio ({@code CREDITOR_CUMULATIVE}), conferido no banco com os {@code check}s da {@code V0813}.
 */
class PublicApiPaymentsIT extends PublicApiTestSupport {

    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    PaymentUseCases payments;
    @Autowired
    IntegrationTokenUseCases tokens;
    @Autowired
    PlatformTokenControlUseCases platformControl;

    @Test
    void creditorIntegrationRegistersWithPaymentsWriteOnlyAndTheCreditorTotalSumsAllTokens() throws Exception {
        // limite padrão de R$ 500,00 por pagamento e por devedor; R$ 2.000,00 por token e pela soma do credor (4 ×)
        List<UUID> titles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            titles.add(titleOfNewDebtor(i));
        }
        IssuedToken first = issue(creditorA, Set.of("payments:write"), null, List.of());
        IssuedToken second = issue(creditorA, Set.of("payments:write"), null, List.of());

        // 4 × R$ 450,00 (dois por token): cada devedor, cada token e o credor abaixo dos limites
        List<String> statuses = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            HttpResponse<String> r = register(i % 2 == 0 ? first : second, titles.get(i), "450.00", "API-" + i);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
            statuses.add((String) body(r).get("status"));
            ids.add(UUID.fromString((String) body(r).get("id")));
        }
        assertThat(statuses).containsOnly("CONFIRMED");

        // o quinto (token 1: R$ 1.350,00 no token, abaixo dos R$ 2.000,00) leva a soma do credor a R$ 2.250,00
        HttpResponse<String> fifth = register(first, titles.get(4), "450.00", "API-4");
        assertThat(fifth.statusCode()).as(fifth.body()).isEqualTo(201);
        assertThat(body(fifth).get("status")).isEqualTo("INFORMED");
        UUID fifthId = UUID.fromString((String) body(fifth).get("id"));
        assertThat(system(() -> jdbc.queryForObject("""
                select count(*) from payment.payment
                 where id = ? and informed_channel = 'INTEGRATION' and review_reason = 'CREDITOR_CUMULATIVE'
                   and review_limit = 2000.00 and review_considered_amount = 2250.00""", Long.class, fifthId)))
                .isEqualTo(1);
        assertThat(system(() -> jdbc.queryForObject("""
                select count(*) from shared.business_event
                 where event_type = 'payment.informed' and attributes->>'reviewReason' = 'CREDITOR_CUMULATIVE'
                   and attributes->>'channel' = 'INTEGRATION'""", Long.class))).isEqualTo(1);

        // na assessoria, quem informou é a integração do credor, com o nome atual do token
        PaymentView view = asManager(() -> payments.get(ids.getFirst()));
        assertThat(view.integration()).isNotNull();
        assertThat(view.integration().tokenId()).isEqualTo(first.details().id());
        assertThat(view.integration().tokenName()).isEqualTo(first.details().name());
        assertThat(view.integration().tokenPrefix()).isNotBlank();
        assertThat(view.integration().tokenRevoked()).isFalse();
        asManager(() -> tokens.revoke(second.details().id(), "Teste de revogação"));
        assertThat(asManager(() -> payments.get(ids.get(1))).integration().tokenRevoked()).isTrue();
        assertThat(system(() -> jdbc.queryForObject(
                "select count(*) from payment.payment where informed_channel = 'INTEGRATION'", Long.class))).isEqualTo(5);
    }

    /**
     * SEG-04 (decisão 1631): token revogado pela assessoria ou pausado pela plataforma, de verdade no publicapi, também
     * exige a confirmação explícita para aprovar; a auditoria grava o estado do token no momento da decisão.
     */
    @Test
    void revokedOrPausedTokenNeedsExplicitConfirmationToApprove() throws Exception {
        UUID titleRevoked = titleOfNewDebtor(11);
        UUID titlePaused = titleOfNewDebtor(12);
        IssuedToken toRevoke = issue(creditorA, Set.of("payments:write"), null, List.of());
        IssuedToken toPause = issue(creditorA, Set.of("payments:write"), null, List.of());
        // acima do limite por pagamento (R$ 500,00): fica em revisão
        HttpResponse<String> r1 = register(toRevoke, titleRevoked, "600.00", "SEG04-R");
        HttpResponse<String> r2 = register(toPause, titlePaused, "600.00", "SEG04-P");
        assertThat(r1.statusCode()).as(r1.body()).isEqualTo(201);
        assertThat(r2.statusCode()).as(r2.body()).isEqualTo(201);
        UUID revokedPayment = UUID.fromString((String) body(r1).get("id"));
        UUID pausedPayment = UUID.fromString((String) body(r2).get("id"));
        assertThat(body(r1).get("status")).isEqualTo("INFORMED");
        assertThat(body(r2).get("status")).isEqualTo("INFORMED");
        assertThat(asManager(() -> payments.get(pausedPayment)).integration().tokenState()).isEqualTo("ACTIVE");

        asManager(() -> tokens.revoke(toRevoke.details().id(), "Teste de revogação"));
        UUID platformTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> tenantAdministration.ensurePlatformTenant("11.444.777/0001-61").tenant().id());
        tenantExecution.callAs(new Actor(Actor.ActorType.USER, Ids.newId(), platformTenant, Ids.newId(), "plataforma",
                EnumSet.of(Permission.TENANT_MANAGE, Permission.PLATFORM_API_TOKENS_MANAGE), CreditorScope.ALL),
                () -> platformControl.pause(tenant, toPause.details().id(), "tráfego anômalo de madrugada", null));

        PaymentView revokedView = asManager(() -> payments.get(revokedPayment));
        assertThat(revokedView.integration().tokenState()).isEqualTo("REVOKED");
        assertThat(revokedView.integration().tokenRevoked()).isTrue();
        PaymentView pausedView = asManager(() -> payments.get(pausedPayment));
        assertThat(pausedView.integration().tokenState()).isEqualTo("PAUSED");
        assertThat(pausedView.integration().tokenRevoked()).isFalse();

        for (UUID paymentId : List.of(revokedPayment, pausedPayment)) {
            assertThatThrownBy(() -> asManager(() -> payments.approve(paymentId, "Conferido", false)))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("code", "payment.integration_token_inactive");
            assertThat(asManager(() -> payments.get(paymentId)).status()).isEqualTo("INFORMED");
            assertThat(asManager(() -> payments.approve(paymentId, "Conferido com o credor", true)).status())
                    .isEqualTo("CONFIRMED");
        }
        assertThat(system(() -> jdbc.queryForObject("""
                select string_agg(metadata ->> 'tokenState', ',' order by metadata ->> 'tokenState')
                  from shared.audit_event
                 where action = 'payment.approved' and entity_id in (?, ?)
                   and metadata ->> 'tokenRevokedAtDecision' = 'true'
                   and metadata ->> 'inactiveTokenConfirmed' = 'true'""", String.class, revokedPayment, pausedPayment)))
                .isEqualTo("PAUSED,REVOKED");
    }

    /**
     * H07 parte 3: depois do estorno de um pagamento recebido pelo credor, o próximo informe da integração do mesmo credor
     * para o mesmo devedor vai à revisão, mesmo abaixo do limite (RS-120). H07b-07 (RS-124): devedor inexistente, de
     * outra assessoria ou de outro credor têm a mesma recusa, conferida antes de qualquer trava.
     */
    @Test
    void reversalSendsTheNextCreditorReportToReviewAndUnlinkedDebtorsGetTheSameRefusal() throws Exception {
        UUID title = titleOfNewDebtor(21);
        IssuedToken token = issue(creditorA, Set.of("payments:write"), null, List.of());
        HttpResponse<String> first = register(token, title, "100.00", "REV-1");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(body(first).get("status")).isEqualTo("CONFIRMED");
        UUID firstId = UUID.fromString((String) body(first).get("id"));
        asManager(() -> payments.reverse(firstId, new PaymentUseCases.ReverseCommand("OTHER",
                "Credor informou por engano")));
        assertThat(system(() -> jdbc.queryForObject("select count(*) from debt.reversal_hold where payment_id = ? "
                + "and state = 'PENDING'", Long.class, firstId))).isEqualTo(1);

        HttpResponse<String> next = register(token, title, "10.00", "REV-2");
        assertThat(next.statusCode()).as(next.body()).isEqualTo(201);
        assertThat(body(next).get("status")).isEqualTo("INFORMED");
        UUID nextId = UUID.fromString((String) body(next).get("id"));
        assertThat(system(() -> jdbc.queryForObject("select count(*) from payment.payment where id = ? "
                + "and review_reason = 'RECENT_CREDITOR_REVERSAL'", Long.class, nextId))).isEqualTo(1);
        // a resposta da API não revela a retenção (contrato 1.0 inalterado)
        assertThat(next.body()).doesNotContain("REVERSAL_HOLD").doesNotContain("reversal_hold");

        // RS-124: mesma recusa para devedor inexistente, de outro credor e de outra assessoria
        UUID otherCreditorTitle = system(() -> {
            UUID debtor = ingestion.upsertDebtor(new DebtorRecord(cpf(22), "Devedora Fictícia B", LocalDate.of(1980, 1, 1),
                    List.of(), null)).id();
            return ingestion.upsertTitle(new TitleRecord(creditorB, portfolioB, debtor, "T-B-22", null, 1,
                    LocalDate.of(2026, 3, 10), new BigDecimal("1000.00"), null, null), Ids.newId()).id();
        });
        UUID otherCreditorDebtor = system(() -> jdbc.queryForObject("select debtor_id from debt.debt_title where id = ?",
                UUID.class, otherCreditorTitle));
        String slug = "pa2-" + UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new br.com.gestorcobranca.organization.application.port.in.TenantAdministration
                        .CreateTenantCommand(slug, "Outra Assessoria Ltda", "Outra", "11.444.777/0001-61")).id());
        UUID otherTenantDebtor = tenantExecution.callInTenant(otherTenant, "test", () -> ingestion.upsertDebtor(
                new DebtorRecord(cpf(23), "Devedora Fictícia T2", LocalDate.of(1980, 1, 1), List.of(), null)).id());
        List<String> refusals = new ArrayList<>();
        for (UUID debtorId : List.of(UUID.randomUUID(), otherCreditorDebtor, otherTenantDebtor)) {
            HttpResponse<String> r = registerFor(token, debtorId, title, "10.00", "RS124-" + refusals.size());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
            Map<String, Object> problem = body(r);
            refusals.add(problem.get("code") + "|" + problem.get("detail") + "|" + problem.get("title"));
        }
        assertThat(refusals).containsOnly(refusals.getFirst());
        assertThat(refusals.getFirst()).startsWith("allocation.target_mismatch|");
    }

    private UUID titleOfNewDebtor(int seed) {
        return system(() -> {
            UUID debtor = ingestion.upsertDebtor(new DebtorRecord(cpf(seed), "Devedora Fictícia " + seed,
                    LocalDate.of(1980, 1, 1), List.of(), null)).id();
            return ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-API-" + seed, null, 1,
                    LocalDate.of(2026, 3, 10), new BigDecimal("1000.00"), null, null), Ids.newId()).id();
        });
    }

    private HttpResponse<String> register(IssuedToken token, UUID titleId, String amount, String reference)
            throws IOException, InterruptedException {
        UUID debtorId = system(() -> jdbc.queryForObject("select debtor_id from debt.debt_title where id = ?", UUID.class,
                titleId));
        return registerFor(token, debtorId, titleId, amount, reference);
    }

    private HttpResponse<String> registerFor(IssuedToken token, UUID debtorId, UUID titleId, String amount,
                                             String reference) throws IOException, InterruptedException {
        String query = Map.of("debtorId", debtorId.toString(), "amount", amount,
                        "paidAt", Instant.now().minus(1, ChronoUnit.HOURS).toString(), "method", "PIX",
                        "targetKind", "TITLES", "itemIds", titleId.toString(), "externalReference", reference)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElseThrow();
        String boundary = "----gc" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"evidence\"; filename=\"comprovante.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(TestPdfs.blank());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/public-api/v1/payments?" + query))
                .header("Authorization", "Bearer " + token.token())
                .header("Idempotency-Key", "pay-" + reference + "-" + UUID.randomUUID().toString().substring(0, 8))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String cpf(int seed) {
        String base = "%09d".formatted(200_000_000 + seed * 7);
        int d1 = digit(base, 10);
        int d2 = digit(base + d1, 11);
        return base + d1 + d2;
    }

    private static int digit(String digits, int weight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * (weight - i);
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
