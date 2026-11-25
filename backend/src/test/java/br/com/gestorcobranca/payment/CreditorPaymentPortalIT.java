package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.Evidence;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.ReportCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases.ReportedPayment;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorStepUp;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.security.StepUpProofs;
import br.com.gestorcobranca.shared.security.StepUpPurpose;
import br.com.gestorcobranca.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pagamento direto informado pela pessoa do credor no portal (ADR 0011, fatia 6): só números dos próprios títulos,
 * sempre em análise (qualquer valor), idempotência no escopo da pessoa, duplicidade e saldo conferidos, comprovante com
 * conteúdo ativo recusado, recusa com motivo para o credor (a nota é interna), destino do acordo que cobre os títulos e
 * o limite de informes em análise. A nova verificação do MFA e o comprovante de uso único têm o IT da identidade
 * ({@code CreditorPortalIdentityIT}); aqui são simulados.
 */
class CreditorPaymentPortalIT extends PaymentTestSupport {

    private static final Set<Permission> REPORTER = Set.of(Permission.CREDITOR_PORTAL_OVERVIEW,
            Permission.CREDITOR_PAYMENT_REPORT);
    private static final Set<Permission> READ_ONLY = Set.of(Permission.CREDITOR_PORTAL_OVERVIEW);

    @MockitoBean
    CreditorStepUp stepUp;
    @MockitoBean
    StepUpProofs stepUpProofs;
    /** Verificador de malware simulado: desligado por padrão; infectado e indisponível nos casos próprios. */
    @MockitoBean
    br.com.gestorcobranca.shared.storage.MalwareScanner scanner;
    @Autowired
    CreditorPaymentPortalUseCases portal;
    @Autowired
    CreditorPaymentUseCases creditorPayments;

    /** Um PDF só (o gerado muda a cada chamada: identificador e data no arquivo). */
    private static final byte[] BLANK = TestPdfs.blank();

    private final UUID person = Ids.newId();
    private final UUID session = Ids.newId();

    @Test
    void creditorReportsDirectPaymentAlwaysReviewedWithItsOwnTitleNumbers() {
        provision("crpay");
        reset(stepUp, stepUpProofs, scanner);
        when(scanner.engine()).thenReturn("teste");
        when(scanner.scan(any())).thenReturn(new br.com.gestorcobranca.shared.storage.MalwareScanner.ScanResult(
                br.com.gestorcobranca.shared.storage.MalwareScanner.Verdict.NOT_SCANNED, null));
        when(stepUp.verify(anyString(), eq(StepUpPurpose.PAYMENT_REPORT), anyString())).thenReturn("comprovante");
        when(stepUpProofs.consume(eq("comprovante"), eq(StepUpPurpose.PAYMENT_REPORT), anyString()))
                .thenReturn(Instant.now());

        // busca só dos próprios títulos, pelo número exato
        assertThat(asCreditor(creditorA, REPORTER, () -> portal.findTitles("T-60"))).singleElement()
                .satisfies(t -> assertThat(t.openBalance()).isEqualByComparingTo("1000.00"));
        assertThat(asCreditor(creditorA, REPORTER, () -> portal.findTitles("B-1"))).isEmpty();
        assertThat(asCreditor(creditorA, REPORTER, () -> portal.findTitles("T-6"))).isEmpty();
        assertThat(asCreditor(creditorB, REPORTER, () -> portal.findTitles("T-60"))).isEmpty();

        // perfil só de consulta não informa (e não gasta o código)
        assertThatThrownBy(() -> asCreditor(creditorA, READ_ONLY, () -> portal.report(command(List.of("T-60"), "300.00"),
                pdf(BLANK), "chave-consulta-1"))).isInstanceOf(ScopeAccessDeniedException.class);
        verify(stepUp, never()).verify(anyString(), any(), anyString());
        // a assessoria não usa a porta do credor
        assertThatThrownBy(() -> asManager(creditorPayments::reported)).isInstanceOf(ScopeAccessDeniedException.class);

        // R$ 300 abaixo de qualquer limite: mesmo assim fica em análise
        ReportedPayment first = asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-60"), "300.00"),
                pdf(BLANK), "chave-informe-0001"));
        assertThat(first.status()).isEqualTo("IN_REVIEW");
        assertThat(count("select count(*) from payment.payment where id = ? and status = 'INFORMED' and review_required "
                + "and informed_channel = 'CREDITOR_PORTAL' and review_reason = 'CREDITOR_PORTAL' and informed_by = ? "
                + "and external_origin = 'CREDITOR_DIRECT' and evidence_scan_verdict is not null and target_kind = 'TITLES'",
                first.id(), person)).isEqualTo(1);
        verify(stepUpProofs).consume(eq("comprovante"), eq(StepUpPurpose.PAYMENT_REPORT),
                eq(CreditorPaymentUseCases.subjectRef(new CreditorPaymentUseCases.ReportCommand(List.of("T-60"),
                        new BigDecimal("300.00"), today.minusDays(1), "PIX", "REF-1", null),
                        sha256(BLANK))));
        // mesma chave e mesmo conteúdo: o mesmo informe; mesma chave com outro conteúdo: 409
        assertThat(asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-60"), "300.00"),
                pdf(BLANK), "chave-informe-0001")).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-60"), "301.00"),
                pdf(BLANK), "chave-informe-0001"))).isInstanceOf(ConflictException.class);
        // outra pessoa do credor informando o mesmo pagamento (outra chave): duplicidade
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-60"), "300.00"),
                pdf(BLANK), "chave-informe-0002")))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "payment.creditor_report_duplicate");
        // valor acima do saldo, título de outro credor e comprovante com JavaScript: recusados
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "900.00"),
                pdf(BLANK), "chave-informe-0003")))
                .hasFieldOrPropertyWithValue("code", "payment.creditor_amount_above_balance");
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("B-1"), "10.00"),
                pdf(BLANK), "chave-informe-0004")))
                .hasFieldOrPropertyWithValue("code", "payment.creditor_title_not_found");
        long before = count("select count(*) from payment.payment where informed_channel = 'CREDITOR_PORTAL'");
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "10.00"),
                pdf(TestPdfs.withJavaScript()), "chave-informe-0005")))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "payment.evidence_active_content");
        assertThat(count("select count(*) from payment.payment where informed_channel = 'CREDITOR_PORTAL'")).isEqualTo(before);

        // comprovante infectado: 422 genérico, MALWARE_DETECTED e nada gravado; verificador fora do ar: nem gasta o código
        when(scanner.scan(any())).thenReturn(new br.com.gestorcobranca.shared.storage.MalwareScanner.ScanResult(
                br.com.gestorcobranca.shared.storage.MalwareScanner.Verdict.INFECTED, "Eicar-Test"));
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "11.00"),
                pdf(BLANK), "chave-informe-0010")))
                .hasFieldOrPropertyWithValue("code", "payment.evidence_rejected");
        assertThat(count("select count(*) from shared.access_log where event_type = 'MALWARE_DETECTED' "
                + "and details ->> 'resource' = 'payment_evidence'")).isEqualTo(1);
        when(scanner.scan(any())).thenThrow(new br.com.gestorcobranca.shared.storage.MalwareScanner
                .MalwareScanUnavailableException("fora do ar", null));
        reset(stepUp);
        when(stepUp.verify(anyString(), eq(StepUpPurpose.PAYMENT_REPORT), anyString())).thenReturn("comprovante");
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "12.00"),
                pdf(BLANK), "chave-informe-0011")))
                .isInstanceOf(br.com.gestorcobranca.shared.storage.MalwareScanner.MalwareScanUnavailableException.class);
        verify(stepUp, never()).verify(anyString(), any(), anyString());
        assertThat(count("select count(*) from payment.payment where informed_channel = 'CREDITOR_PORTAL'")).isEqualTo(before);
        org.mockito.Mockito.doReturn(new br.com.gestorcobranca.shared.storage.MalwareScanner.ScanResult(
                br.com.gestorcobranca.shared.storage.MalwareScanner.Verdict.NOT_SCANNED, null)).when(scanner).scan(any());

        // recusa: o motivo para o credor é obrigatório; a nota fica com a assessoria
        assertThatThrownBy(() -> asFinance1(() -> payments.reject(first.id(), "Comprovante de outra conta")))
                .hasFieldOrPropertyWithValue("code", "payment.creditor_rejection_reason_required");
        asFinance1(() -> payments.reject(first.id(), "Comprovante de outra conta", "PROOF_INVALID"));
        ReportedPayment rejected = asCreditor(creditorA, REPORTER, portal::reported).stream()
                .filter(p -> p.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectionReason()).isEqualTo("PROOF_INVALID");
        assertThat(rejected.toString()).doesNotContain("outra conta");
        assertThat(asCreditor(creditorB, REPORTER, portal::reported)).isEmpty();

        // aprovado por outra pessoa: confirmado
        ReportedPayment second = asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "200.00"),
                pdf(BLANK), "chave-informe-0006"));
        asFinance1(() -> payments.approve(second.id(), null));
        assertThat(asCreditor(creditorA, REPORTER, portal::reported)).filteredOn(p -> p.id().equals(second.id()))
                .singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("CONFIRMED"));
        // repetição com a mesma chave depois da aprovação (saldo já baixado): devolve o mesmo informe
        assertThat(asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "200.00"),
                pdf(BLANK), "chave-informe-0006")).id()).isEqualTo(second.id());

        // títulos num acordo ativo: o destino é o acordo que cobre os títulos (parcelas em aberto)
        formalizeAgreement(3);
        ReportedPayment underAgreement = asCreditor(creditorA, REPORTER, () -> portal.report(
                command(List.of("T-60"), "50.00"), pdf(BLANK), "chave-informe-0007"));
        assertThat(count("select count(*) from payment.payment where id = ? and target_kind = 'AGREEMENT' "
                + "and target_agreement_id is not null", underAgreement.id())).isEqualTo(1);

        // limite de informes em análise por credor
        for (int i = 0; i < CreditorPaymentServiceLimit.MAX - 1; i++) {
            String amount = "1." + String.format("%02d", i + 1);
            int n = i;
            asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), amount), pdf(BLANK),
                    "chave-limite-%04d".formatted(n)));
        }
        assertThatThrownBy(() -> asCreditor(creditorA, REPORTER, () -> portal.report(command(List.of("T-30"), "2.50"),
                pdf(BLANK), "chave-limite-final")))
                .hasFieldOrPropertyWithValue("code", "payment.creditor_report_limit");
    }

    /** O limite do serviço (20), repetido aqui para o laço do teste. */
    private static final class CreditorPaymentServiceLimit {
        static final int MAX = 20;
    }

    private ReportCommand command(List<String> numbers, String amount) {
        return new ReportCommand(numbers, new BigDecimal(amount), today.minusDays(1), "PIX", "REF-1", null, "123456");
    }

    private static Evidence pdf(byte[] content) {
        return new Evidence("comprovante.pdf", "application/pdf", content.length, new ByteArrayInputStream(content));
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Como na requisição do portal: o ator na sessão, sem transação aberta (o portal abre a sua). */
    private <T> T asCreditor(UUID creditorId, Set<Permission> permissions, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(ActorAuthentication.of(Actor.creditor(tenant, person, session, creditorId, permissions)));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
