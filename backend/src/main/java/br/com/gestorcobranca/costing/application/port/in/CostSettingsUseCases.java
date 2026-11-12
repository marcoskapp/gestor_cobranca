package br.com.gestorcobranca.costing.application.port.in;

import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Cadastros de custo (desenho §3 e contrato §10.2): faixas de custo de pessoas e a faixa de cada usuário
 * ({@code COST_RATE_MANAGE}), tarifas de canal, câmbio e custos fixos ({@code COST_MANAGE}). Toda alteração grava
 * auditoria; cadastros com vigência nunca são apagados, só encerrados. Os cadastros valem para a assessoria inteira:
 * alterá-los exige acesso a todos os credores (custo fixo direto a um credor exige só o credor).
 * Alterações valem para os custos a partir da próxima apuração (diária) ou de uma reapuração do período.
 */
public interface CostSettingsUseCases {

    // ------------------------------------------------------------------ faixas de custo de pessoas

    List<BandView> bands();

    /**
     * Nova faixa ou nova vigência de uma faixa existente (mesmo nome): a vigência em aberto que começou antes é
     * encerrada na véspera; qualquer outra sobreposição é recusada.
     */
    BandView createBand(CreateBandCommand command);

    BandView endBand(UUID bandId, LocalDate validTo);

    /** Usuários habilitados da assessoria com a faixa vigente hoje (sem o valor-hora). */
    List<UserBandView> userBands();

    /** Histórico das faixas de uma pessoa. */
    List<UserBandView> userBandHistory(UUID userId);

    /**
     * Faixa da pessoa a partir de uma data: encerra a vigência em aberto na véspera. {@code bandName} nulo = sem faixa
     * a partir da data. Data igual ao início de uma vigência que ainda não começou corrige a faixa dela.
     */
    UserBandView assignUserBand(UUID userId, AssignUserBandCommand command);

    // ------------------------------------------------------------------ tarifas de canal

    List<TariffView> tariffs();

    TariffView createTariff(CreateTariffCommand command);

    TariffView endTariff(UUID tariffId, LocalDate validTo);

    // ------------------------------------------------------------------ câmbio

    List<FxRateView> fxRates();

    /** Câmbio do mês (cria ou corrige; a correção fica na auditoria). */
    FxRateView saveFxRate(SaveFxRateCommand command);

    // ------------------------------------------------------------------ custos fixos

    /** Custos fixos (filtro opcional por mês, inclusive). Escopo restrito vê só os custos diretos dos seus credores. */
    List<OverheadView> overheads(YearMonth from, YearMonth to);

    /** Cria o custo fixo e refaz na hora o rateio do mês. */
    OverheadView createOverhead(CreateOverheadCommand command);

    /** Exclui o custo fixo (auditado, com o motivo) e refaz na hora o rateio do mês. */
    void deleteOverhead(UUID overheadId, String reason);

    // ------------------------------------------------------------------ comandos e visões

    record CreateBandCommand(String name, BigDecimal hourlyCost, LocalDate validFrom) {
    }

    /**
     * @param hourlyCost valor-hora da faixa; nulo para quem não tem {@code COST_RATE_MANAGE} (só o nome é mostrado)
     * @param current    vigente hoje
     */
    record BandView(UUID id, String name, BigDecimal hourlyCost, LocalDate validFrom, LocalDate validTo,
                    boolean current) {
    }

    record AssignUserBandCommand(String bandName, LocalDate validFrom) {
    }

    /**
     * Faixa de uma pessoa (sem o valor-hora). Sem faixa: {@code bandName}, {@code validFrom} e {@code validTo} nulos.
     *
     * @param active usuário ativo (ou aguardando ativação)
     */
    record UserBandView(UUID userId, String userName, boolean active, String bandName, LocalDate validFrom,
                        LocalDate validTo) {
    }

    record CreateTariffCommand(TariffChannel channel, BigDecimal unitCost, LocalDate validFrom) {
    }

    record TariffView(UUID id, TariffChannel channel, BigDecimal unitCost, LocalDate validFrom, LocalDate validTo,
                      boolean current) {
    }

    record SaveFxRateCommand(YearMonth month, BigDecimal usdBrl) {
    }

    /** @param month mês no formato {@code AAAA-MM} */
    record FxRateView(UUID id, String month, BigDecimal usdBrl) {
    }

    /** @param allocation nula = {@code HOURS} (padrão); {@code creditorId} obrigatório só com {@code CREDITOR} */
    record CreateOverheadCommand(YearMonth month, String description, BigDecimal amount, OverheadAllocation allocation,
                                 UUID creditorId) {
    }

    /** @param month mês no formato {@code AAAA-MM} */
    record OverheadView(UUID id, String month, String description, BigDecimal amount, OverheadAllocation allocation,
                        UUID creditorId, String creditorName) {
    }
}
