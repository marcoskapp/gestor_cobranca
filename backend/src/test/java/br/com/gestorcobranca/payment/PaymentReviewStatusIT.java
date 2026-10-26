package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases.AgreementReviewStatus;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases.CoverageView;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases.ReviewItem;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Situação de revisão dos pagamentos externos no acordo e no caso (quem negocia sabe o que aguarda a
 * aprovação de outra pessoa e o que foi recusado): previsão das parcelas cobertas, motivo da recusa só
 * para quem informou ou tem {@code PAYMENT_VIEW}, janela dos recusados, resumo em lote sem N+1, filtro
 * de pagamentos por acordo, escopo por credor e permissão ({@code AGREEMENT_VIEW}) também por HTTP.
 */
class PaymentReviewStatusIT extends PaymentTestSupport {

    @Autowired
    PaymentReviewStatusUseCases reviewStatus;
    @Autowired
    PaymentSettingsUseCases settings;
    @Autowired
    WebApplicationContext webApplicationContext;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private PaymentView informAgreement(UUID agreementId, BigDecimal amount, List<UUID> installments, String reference) {
        return asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor, amount, paidAt,
                "TRANSFER", "AGENCY_ACCOUNT", reference, "Depósito do devedor", new TargetInput("AGREEMENT", agreementId,
                installments, null)), pdf(), null));
    }

    /** Perfil com AGREEMENT_VIEW e sem PAYMENT_VIEW (jurídico). */
    private <T> T asLegal(Supplier<T> action) {
        return as(Ids.newId(), DefaultRoles.JURIDICO, CreditorScope.ALL, action);
    }

    @Test
    void negotiatorSeesPendingAndRejectedPaymentsOfTheAgreementWithLeastPrivilege() throws Exception {
        provision("revisao");
        asAdmin(() -> settings.update(new SettingsCommand(new BigDecimal("50.00"), 86_400, 30, 3)));
        AgreementView agreement = formalizeAgreement(3);
        List<InstallmentView> installments = agreement.installments();
        InstallmentView first = installments.get(0);
        InstallmentView second = installments.get(1);
        InstallmentView third = installments.get(2);

        // nada pendente: resumo vazio, sem selo na listagem
        AgreementReviewStatus empty = asOperator(() -> reviewStatus.forAgreement(agreement.id()));
        assertThat(empty.pendingCount()).isZero();
        assertThat(empty.pending()).isEmpty();
        assertThat(empty.expectedCoverage()).isEmpty();
        assertThat(asOperator(() -> reviewStatus.forAgreements(List.of(agreement.id())))).isEmpty();

        // dois informados acima do limite: um para a 2ª parcela, outro sem parcela (em aberto, por número)
        PaymentView toSecond = informAgreement(agreement.id(), second.amount(), List.of(second.id()), "DOC-2");
        PaymentView open = informAgreement(agreement.id(), first.amount().add(BigDecimal.ONE), List.of(), "DOC-1");
        assertThat(toSecond.status()).isEqualTo("INFORMED");
        assertThat(open.status()).isEqualTo("INFORMED");
        // pagamento de outro destino (título do credor B) não entra no acordo
        PaymentView otherTarget = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal("80.00"), paidAt, "CASH", "CREDITOR_DIRECT", null, null,
                new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null));

        AgreementReviewStatus status = asOperator(() -> reviewStatus.forAgreement(agreement.id()));
        assertThat(status.pendingCount()).isEqualTo(2);
        assertThat(status.pendingTotal()).isEqualByComparingTo(second.amount().add(first.amount()).add(BigDecimal.ONE));
        assertThat(status.pending()).extracting(ReviewItem::paymentId).containsExactly(toSecond.id(), open.id());
        assertThat(status.pending().getFirst()).satisfies(i -> {
            assertThat(i.status()).isEqualTo("INFORMED");
            assertThat(i.informedBy()).isEqualTo(operator);
            assertThat(i.informedAt()).isNotNull();
            assertThat(i.targetKind()).isEqualTo("AGREEMENT");
            assertThat(i.requestedItemIds()).containsExactly(second.id());
            assertThat(i.method()).isEqualTo("TRANSFER");
        });
        // previsão na ordem do registro: o 1º cobre a 2ª parcela; o 2º, a 1ª e R$ 1,00 da 3ª (a 2ª já coberta)
        assertThat(status.expectedCoverage()).extracting(CoverageView::installmentId, CoverageView::paymentId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(second.id(), toSecond.id()),
                        org.assertj.core.groups.Tuple.tuple(first.id(), open.id()),
                        org.assertj.core.groups.Tuple.tuple(third.id(), open.id()));
        assertThat(status.expectedCoverage().get(2).amount()).isEqualByComparingTo("1.00");
        // nada é baixado enquanto aguarda revisão
        assertThat(agreement(agreement.id()).paidAmount()).isEqualByComparingTo("0");

        // recusa com motivo: o negociador precisa agir
        asFinance1(() -> payments.reject(open.id(), "Comprovante ilegível, peça outro ao devedor"));
        AgreementReviewStatus afterReject = asOperator(() -> reviewStatus.forAgreement(agreement.id()));
        assertThat(afterReject.pendingCount()).isEqualTo(1);
        assertThat(afterReject.expectedCoverage()).extracting(CoverageView::installmentId).containsExactly(second.id());
        assertThat(afterReject.rejected()).singleElement().satisfies(i -> {
            assertThat(i.paymentId()).isEqualTo(open.id());
            assertThat(i.reviewedBy()).isEqualTo(finance1);
            assertThat(i.reviewedAt()).isNotNull();
            assertThat(i.rejectionReason()).isEqualTo("Comprovante ilegível, peça outro ao devedor");
            assertThat(i.reasonRestricted()).isFalse();
        });
        // menor privilégio: quem vê o acordo sem PAYMENT_VIEW sabe que há recusa, mas não lê o motivo
        AgreementReviewStatus legal = asLegal(() -> reviewStatus.forAgreement(agreement.id()));
        assertThat(legal.pendingCount()).isEqualTo(1);
        assertThat(legal.rejected()).singleElement().satisfies(i -> {
            assertThat(i.rejectionReason()).isNull();
            assertThat(i.reasonRestricted()).isTrue();
        });
        // sem AGREEMENT_VIEW (analista de carteiras), nada
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.ANALISTA_CARTEIRA, CreditorScope.ALL,
                () -> reviewStatus.forAgreement(agreement.id()))).isInstanceOf(ScopeAccessDeniedException.class);

        // listagem: um resumo por acordo, numa consulta; acordos sem pendência nem recusa não aparecem
        UUID unknown = Ids.newId();
        assertThat(asOperator(() -> reviewStatus.forAgreements(List.of(agreement.id(), unknown)))).singleElement()
                .satisfies(b -> {
                    assertThat(b.agreementId()).isEqualTo(agreement.id());
                    assertThat(b.pendingCount()).isEqualTo(1);
                    assertThat(b.pendingTotal()).isEqualByComparingTo(second.amount());
                    assertThat(b.lastInformedAt()).isNotNull();
                    assertThat(b.rejectedCount()).isEqualTo(1);
                    assertThat(b.lastRejectedAt()).isNotNull();
                });
        List<UUID> tooMany = Stream.concat(Stream.of(agreement.id()),
                Collections.nCopies(PaymentReviewStatusUseCases.MAX_AGREEMENTS, 0).stream().map(x -> Ids.newId())).toList();
        assertThatThrownBy(() -> asOperator(() -> reviewStatus.forAgreements(tooMany)))
                .isInstanceOf(BusinessRuleException.class);

        // ficha do caso: devedor no credor (acordo e títulos avulsos do credor; o credor B fica de fora)
        assertThat(asOperator(() -> reviewStatus.forDebtor(creditorA, debtor))).satisfies(d -> {
            assertThat(d.pendingCount()).isEqualTo(1);
            assertThat(d.pending()).extracting(ReviewItem::paymentId).containsExactly(toSecond.id());
            assertThat(d.rejected()).extracting(ReviewItem::paymentId).containsExactly(open.id());
        });

        // escopo por credor: operador só do credor B não vê o acordo do credor A
        assertThatThrownBy(() -> asOperatorB(() -> reviewStatus.forAgreement(agreement.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asOperatorB(() -> reviewStatus.forAgreements(List.of(agreement.id())))).isEmpty();
        assertThatThrownBy(() -> asOperatorB(() -> reviewStatus.forDebtor(creditorA, debtor)))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // pagamentos do acordo (PAYMENT_VIEW): destino solicitado, inclusive pendentes e recusados
        assertThat(asFinance1(() -> payments.search(new PaymentFilter(null, null, null, null, null, null, null, null,
                agreement.id()), PageQuery.of(0, 20))).content()).extracting(PaymentView::id)
                .containsExactlyInAnyOrder(toSecond.id(), open.id()).doesNotContain(otherTarget.id());
        assertThat(asFinance1(() -> payments.search(new PaymentFilter(null, null, "INFORMED", null, null, null, null, null,
                agreement.id()), PageQuery.of(0, 20))).content()).extracting(PaymentView::id).containsExactly(toSecond.id());

        // HTTP: AGREEMENT_VIEW basta; a resposta não traz observação, evidência nem referência
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        ActorAuthentication legalAuth = auth(DefaultRoles.JURIDICO);
        mvc.perform(get("/api/v1/payment/review-status/agreements/" + agreement.id()).with(authentication(legalAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.pending[0].paymentId").value(toSecond.id().toString()))
                .andExpect(jsonPath("$.rejected[0].reasonRestricted").value(true))
                .andExpect(content().string(not(containsString("Depósito do devedor"))))
                .andExpect(content().string(not(containsString("DOC-2"))))
                .andExpect(content().string(not(containsString("evidence"))));
        mvc.perform(get("/api/v1/payment/review-status/agreements").param("ids", agreement.id() + "," + unknown)
                        .with(authentication(legalAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/payment/review-status/debtors/" + debtor).param("creditorId", creditorA.toString())
                        .with(authentication(legalAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount").value(1));
        mvc.perform(get("/api/v1/payment/review-status/agreements/" + agreement.id())
                        .with(authentication(auth(DefaultRoles.ANALISTA_CARTEIRA))))
                .andExpect(status().isForbidden());
        // o detalhe completo do pagamento continua exigindo PAYMENT_VIEW
        mvc.perform(get("/api/v1/payment/payments/" + toSecond.id()).with(authentication(legalAuth)))
                .andExpect(status().isForbidden());

        // aprovado: deixa de estar pendente e a parcela é baixada
        asFinance2(() -> payments.approve(toSecond.id(), "Conferido no extrato"));
        settle();
        awaitTrue("parcela baixada", () -> agreement(agreement.id()).installments().get(1).paidAmount()
                .compareTo(second.amount()) == 0);
        AgreementReviewStatus approved = asOperator(() -> reviewStatus.forAgreement(agreement.id()));
        assertThat(approved.pendingCount()).isZero();
        assertThat(approved.expectedCoverage()).isEmpty();
        assertThat(approved.rejected()).hasSize(1);

        // fora da janela (30 dias após a recusa), a recusa deixa de aparecer
        system(() -> jdbcTemplate.update("update payment.payment set reviewed_at = reviewed_at - interval '31 days' "
                + "where id = ?", open.id()));
        assertThat(asOperator(() -> reviewStatus.forAgreement(agreement.id())).rejected()).isEmpty();
        assertThat(asOperator(() -> reviewStatus.forAgreements(List.of(agreement.id())))).isEmpty();
    }

    private ActorAuthentication auth(String role) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), role,
                permissions, CreditorScope.ALL));
    }
}
