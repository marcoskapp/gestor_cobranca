package br.com.gestorcobranca.legal;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.AuthorizationView;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.DecisionCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.SyncResult;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.ReferCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.TransitionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentInput;
import br.com.gestorcobranca.legal.application.port.out.CourtDataProvider;
import br.com.gestorcobranca.legal.application.service.LegalTestHooks;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Como um adaptador real se encaixa: um {@link CourtDataProvider} automático (aqui um dublê de
 * teste; em produção, o adaptador DataJud habilitado por {@code app.legal.providers.court-data})
 * passa a capturar andamentos — idempotentes pelo identificador externo, com os mesmos efeitos do
 * lançamento manual (citação interrompe a prescrição e leva o caso a IN_PROGRESS) — pela API e
 * pela varredura diária.
 */
@Import(LegalProviderPluggingIT.AutomaticCourtData.class)
class LegalProviderPluggingIT extends LegalTestSupport {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @TestConfiguration
    static class AutomaticCourtData {

        @Bean
        @Primary
        CourtDataProvider testCourtDataProvider() {
            return new CourtDataProvider() {
                @Override
                public String code() {
                    return "test-court";
                }

                @Override
                public boolean automatic() {
                    return true;
                }

                @Override
                public CourtCapture fetch(CourtQuery query) {
                    LocalDate today = LocalDate.now(ZONE);
                    return new CourtCapture(true, "Tribunal de Justiça de São Paulo", "2ª Vara Cível", "Monitória",
                            List.of(new CapturedMovement(query.cnjNumber() + "-1", today, "DISTRIBUTION", "Distribuído"),
                                    new CapturedMovement(query.cnjNumber() + "-2", today, "CITATION", "Citação positiva"),
                                    new CapturedMovement(query.cnjNumber() + "-3", today, "TPU-12345", "Conclusos")));
                }
            };
        }
    }

    @Test
    void automaticCourtDataProviderCapturesMovementsIdempotently() {
        provision("lprov");
        UUID debtor = debtor("526.018.159-06");
        load(creditorA, portfolioA, debtor, new LoadedTitle("M-1", "C-M-1", LocalDate.of(2026, 3, 10), "4000.00"));
        CaseView c = activeCase(debtor, portfolioA);
        MatterView matter = asLawyer(() -> matters.refer(new ReferCommand(c.id(), Set.of(), "Monitória")));
        UUID id = matter.id();
        asLawyer(() -> matters.transition(id, new TransitionCommand("UNDER_REVIEW", "aceite", null, null)));
        asLawyer(() -> viability.record(id, new AssessmentCommand(new AssessmentInput(null, null, null,
                new BigDecimal("500.00")), true, List.of("MONITORY_ACTION"), List.of(), null, null)));
        asLawyer(() -> matters.transition(id, new TransitionCommand("AWAITING_AUTHORIZATION", "viável", null, null)));
        AuthorizationView pending = asLawyer(() -> authorizations.list(id)).getFirst();
        UUID evidence = upload(lawyer, DefaultRoles.JURIDICO, id, "AUTHORIZATION_EVIDENCE", "MATTER", null).id();
        LocalDate today = LocalDate.now(ZONE);
        asLawyer(() -> authorizations.decide(pending.id(), new DecisionCommand("GRANTED", Set.of("MONITORY_ACTION"),
                new BigDecimal("500.00"), today, "SIGNED_DOCUMENT", "Diretor jurídico do credor", evidence, null)));
        LawsuitView lawsuit = asLawyer(() -> lawsuits.register(id, new LawsuitCommand(LegalFlowIT.cnj(today.getYear()),
                "MONITORY_ACTION", null, null, null, null, today, null, null)));
        assertThat(lawsuit.provider()).isEqualTo("test-court");
        assertThat(asLawyer(() -> matters.get(id)).stage()).isEqualTo("FILED");

        SyncResult first = asLawyer(() -> lawsuits.synchronize(lawsuit.id()));
        assertThat(first.captured()).isEqualTo(3);
        assertThat(first.duplicates()).isZero();
        assertThat(asLawyer(() -> matters.get(id)).stage()).isEqualTo("IN_PROGRESS");
        assertThat(count("select count(*) from debt.prescription_interruption where origin = 'LEGAL' and type = 'CITATION'"))
                .isEqualTo(1);
        List<MovementView> captured = asLawyer(() -> lawsuits.movements(lawsuit.id(), new PageQuery(0, 10))).content();
        assertThat(captured).extracting(MovementView::source).containsOnly("PROVIDER");
        assertThat(captured).extracting(MovementView::type).contains("CITATION", "OTHER");
        LawsuitView described = asLawyer(() -> lawsuits.get(lawsuit.id()));
        assertThat(described.courtUnit()).isEqualTo("2ª Vara Cível");
        assertThat(described.lastSyncedAt()).isNotNull();

        // reentrega pela varredura diária: nada duplicado
        LegalTestHooks.runDaily(applicationContext, tenant);
        assertThat(count("select count(*) from legal.case_movement where lawsuit_id = ?", lawsuit.id())).isEqualTo(3);
        assertThat(asLawyer(() -> lawsuits.synchronize(lawsuit.id())).duplicates()).isEqualTo(3);
    }
}
