package br.com.gestorcobranca.legal.domain.service;

import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.model.MatterFlag;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Análise de viabilidade (etapa 2): valor da dívida, garantias e avaliações, outros bens
 * localizados, custas previstas, valor recuperável estimado, custo × benefício e prescrição — e,
 * a partir deles, as vias juridicamente disponíveis e as sugeridas. Puro, sem dependências.
 * <p>
 * Fórmula (documentada em docs/design/juridico.md §B.6):
 * <ul>
 *   <li>dívida cobrável = saldo dos títulos cuja pretensão de cobrança não prescreveu;</li>
 *   <li>garantia recuperável = soma das avaliações das garantias exequíveis (ativa, em execução,
 *       apreendida ou consolidada) × taxa de realização (deságio de liquidação);</li>
 *   <li>outros bens recuperáveis = valor dos bens localizados na pesquisa patrimonial × taxa de realização;</li>
 *   <li>base = garantia + outros bens, ou a dívida cobrável quando não há patrimônio identificado;</li>
 *   <li>valor recuperável estimado = mín(dívida cobrável, base) × probabilidade de êxito;</li>
 *   <li>benefício líquido = valor recuperável estimado − custas previstas.</li>
 * </ul>
 * Vias: protesto, negativação, monitória e ação de cobrança exigem dívida cobrável; execução exige
 * título executivo com a pretensão executiva vigente; busca e apreensão exige alienação fiduciária
 * de veículo exequível; a execução extrajudicial da garantia exige alienação fiduciária (imóvel ou
 * veículo) ou hipoteca exequível.
 */
public final class ViabilityCalculator {

    private static final Set<String> ENFORCEABLE_COLLATERAL_STATUS = Set.of("ACTIVE", "UNDER_ENFORCEMENT", "SEIZED",
            "CONSOLIDATED");
    private static final Set<String> OPEN_COLLATERAL_STATUS = Set.of("ACTIVE", "UNDER_ENFORCEMENT");
    private static final String VEHICLE_FIDUCIARY = "VEHICLE_FIDUCIARY_ALIENATION";
    private static final Set<String> EXTRAJUDICIAL_ENFORCEABLE = Set.of("REAL_ESTATE_FIDUCIARY_ALIENATION", "MORTGAGE",
            VEHICLE_FIDUCIARY);
    private static final Set<String> REAL_ESTATE_ENFORCEABLE = Set.of("REAL_ESTATE_FIDUCIARY_ALIENATION", "MORTGAGE");

    private ViabilityCalculator() {
    }

    /**
     * Prescrição de um título do caso jurídico (pretensões executiva e de cobrança).
     *
     * @param executionDate       data estimada da pretensão executiva (nula sem regra ou título não executivo)
     * @param collectionDate      data estimada da pretensão de cobrança (nula sem regra)
     * @param ruleValidated       todas as regras aplicadas foram validadas pelo jurídico
     */
    public record TitleFact(UUID titleId, Money openBalance, boolean executiveTitle, LocalDate executionDate,
                            boolean executionPrescribed, LocalDate collectionDate, boolean collectionPrescribed,
                            boolean hasRule, boolean ruleValidated) {
    }

    /** @param type nome do enum {@code CollateralType} do debt; @param status nome de {@code CollateralStatus} */
    public record CollateralFact(UUID collateralId, String type, String status, Money estimatedValue) {
    }

    /**
     * @param realizationRate    deságio de liquidação dos bens (0–100%)
     * @param successProbability probabilidade de êxito estimada (0–100%)
     * @param warningDays        janela de atenção de prescrição da assessoria
     */
    public record Input(List<TitleFact> titles, List<CollateralFact> collaterals, Money otherAssetsValue,
                        Percentage realizationRate, Percentage successProbability, Money expectedCosts, LocalDate today,
                        int warningDays) {

        public Input {
            titles = List.copyOf(titles);
            collaterals = collaterals == null ? List.of() : List.copyOf(collaterals);
            otherAssetsValue = otherAssetsValue == null ? Money.ZERO : otherAssetsValue;
            expectedCosts = expectedCosts == null ? Money.ZERO : expectedCosts;
        }
    }

    /**
     * @param collectibleDebt     saldo dos títulos ainda cobráveis
     * @param costBenefitRatio    valor recuperável ÷ custas previstas (nulo sem custas)
     * @param economicallyViable  dívida cobrável e benefício líquido positivo
     */
    public record Result(Money debtAmount, Money collectibleDebt, Money collateralValue, Money collateralRecoverable,
                         Money otherAssetsRecoverable, Money estimatedRecoverable, Money expectedCosts, Money netBenefit,
                         BigDecimal costBenefitRatio, Set<LegalRoute> availableRoutes, List<LegalRoute> suggestedRoutes,
                         Set<MatterFlag> flags, boolean economicallyViable) {
    }

    public static Result calculate(Input in) {
        if (in.otherAssetsValue().isNegative() || in.expectedCosts().isNegative()) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException("legal_viability.amount_invalid",
                    "Valores não podem ser negativos");
        }
        Money debt = Money.ZERO;
        Money collectible = Money.ZERO;
        boolean executionAvailable = false;
        Set<MatterFlag> flags = EnumSet.noneOf(MatterFlag.class);
        for (TitleFact t : in.titles()) {
            debt = debt.plus(t.openBalance());
            if (t.collectionPrescribed()) {
                flags.add(MatterFlag.PRESCRIBED_TITLES_INCLUDED);
                continue;
            }
            collectible = collectible.plus(t.openBalance());
            if (t.executiveTitle()) {
                if (t.executionPrescribed()) {
                    flags.add(MatterFlag.EXECUTION_PRESCRIBED);
                } else {
                    executionAvailable = true;
                }
            }
            if (near(t.collectionDate(), in) || (t.executiveTitle() && !t.executionPrescribed() && near(t.executionDate(), in))) {
                flags.add(MatterFlag.PRESCRIPTION_NEAR);
            }
            if (t.hasRule() && !t.ruleValidated()) {
                flags.add(MatterFlag.PRESCRIPTION_RULE_UNVALIDATED);
            }
        }

        Money collateralValue = Money.ZERO;
        boolean vehicleFiduciary = false;
        boolean extrajudicialCollateral = false;
        boolean realEstate = false;
        for (CollateralFact c : in.collaterals()) {
            if (c.estimatedValue() != null && ENFORCEABLE_COLLATERAL_STATUS.contains(c.status())) {
                collateralValue = collateralValue.plus(c.estimatedValue());
            }
            if (OPEN_COLLATERAL_STATUS.contains(c.status())) {
                vehicleFiduciary |= VEHICLE_FIDUCIARY.equals(c.type());
                extrajudicialCollateral |= EXTRAJUDICIAL_ENFORCEABLE.contains(c.type());
                realEstate |= REAL_ESTATE_ENFORCEABLE.contains(c.type());
            }
        }
        BigDecimal realization = in.realizationRate().asFraction();
        Money collateralRecoverable = collateralValue.times(realization);
        Money otherRecoverable = in.otherAssetsValue().times(realization);
        Money assets = collateralRecoverable.plus(otherRecoverable);
        Money base = assets.isPositive() ? assets : collectible;
        Money recoverable = collectible.min(base).times(in.successProbability().asFraction());
        Money net = recoverable.minus(in.expectedCosts());
        BigDecimal ratio = in.expectedCosts().isPositive()
                ? recoverable.amount().divide(in.expectedCosts().amount(), 2, RoundingMode.HALF_EVEN) : null;
        boolean economicallyViable = collectible.isPositive() && net.isPositive();
        if (collectible.isPositive() && !economicallyViable) {
            flags.add(MatterFlag.NEGATIVE_COST_BENEFIT);
        }

        Set<LegalRoute> available = EnumSet.noneOf(LegalRoute.class);
        if (collectible.isPositive()) {
            available.addAll(EnumSet.of(LegalRoute.PROTEST, LegalRoute.CREDIT_BUREAU_LISTING, LegalRoute.MONITORY_ACTION,
                    LegalRoute.COLLECTION_ACTION));
            if (executionAvailable) {
                available.add(LegalRoute.EXECUTION);
            }
            if (vehicleFiduciary) {
                available.add(LegalRoute.SEARCH_AND_SEIZURE);
            }
            if (extrajudicialCollateral) {
                available.add(LegalRoute.EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT);
            }
        }
        List<LegalRoute> suggested = new ArrayList<>();
        if (economicallyViable) {
            if (realEstate) {
                suggested.add(LegalRoute.EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT);
            }
            if (vehicleFiduciary) {
                suggested.add(LegalRoute.SEARCH_AND_SEIZURE);
            }
            if (flags.contains(MatterFlag.PRESCRIPTION_NEAR)) {
                suggested.add(LegalRoute.PROTEST);
            }
            suggested.add(executionAvailable ? LegalRoute.EXECUTION : LegalRoute.MONITORY_ACTION);
            if (!suggested.contains(LegalRoute.PROTEST)) {
                suggested.add(LegalRoute.PROTEST);
            }
            suggested.add(LegalRoute.CREDIT_BUREAU_LISTING);
        }
        return new Result(debt, collectible, collateralValue, collateralRecoverable, otherRecoverable, recoverable,
                in.expectedCosts(), net, ratio, Set.copyOf(available), List.copyOf(suggested),
                flags.isEmpty() ? Set.of() : Set.copyOf(flags), economicallyViable);
    }

    private static boolean near(LocalDate estimated, Input in) {
        if (estimated == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(in.today(), estimated);
        return days > 0 && days <= in.warningDays();
    }
}
