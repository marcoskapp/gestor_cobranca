package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.domain.model.ChannelTariff;
import br.com.gestorcobranca.costing.domain.model.CostBand;
import br.com.gestorcobranca.costing.domain.model.Dated;
import br.com.gestorcobranca.costing.domain.model.FxRate;
import br.com.gestorcobranca.costing.domain.model.Overhead;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.UserCostBand;
import br.com.gestorcobranca.costing.domain.repository.ChannelTariffRepository;
import br.com.gestorcobranca.costing.domain.repository.CostBandRepository;
import br.com.gestorcobranca.costing.domain.repository.FxRateRepository;
import br.com.gestorcobranca.costing.domain.repository.OverheadRepository;
import br.com.gestorcobranca.costing.domain.repository.UserCostBandRepository;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cadastros de custo com vigência e auditoria (desenho §3). Os cadastros valem para a assessoria inteira: alterar
 * exige acesso a todos os credores, exceto o custo fixo direto a um credor (exige só o credor). O valor-hora é de uma
 * faixa (cargo), nunca de uma pessoa; a lista "usuário → faixa" não mostra valores.
 */
@Service
@Transactional
class CostSettingsService implements CostSettingsUseCases {

    private final CostBandRepository bands;
    private final UserCostBandRepository userBands;
    private final ChannelTariffRepository tariffs;
    private final FxRateRepository fxRates;
    private final OverheadRepository overheads;
    private final UserDirectory users;
    private final CreditorUseCases creditors;
    private final CostRefreshService refresh;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;
    private final AuditTrail audit;
    private final Clock clock;

    CostSettingsService(CostBandRepository bands, UserCostBandRepository userBands, ChannelTariffRepository tariffs,
                        FxRateRepository fxRates, OverheadRepository overheads, UserDirectory users,
                        CreditorUseCases creditors, CostRefreshService refresh, CurrentActor currentActor,
                        AccessScope accessScope, AccessLogger accessLogger, AuditTrail audit, Clock clock) {
        this.bands = bands;
        this.userBands = userBands;
        this.tariffs = tariffs;
        this.fxRates = fxRates;
        this.overheads = overheads;
        this.users = users;
        this.creditors = creditors;
        this.refresh = refresh;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================== faixas de custo de pessoas

    @Override
    @Transactional(readOnly = true)
    public List<BandView> bands() {
        requireAny("costing_bands", Permission.COST_VIEW, Permission.COST_RATE_MANAGE);
        LocalDate today = today();
        // valor-hora (remuneração de um cargo) só para quem gerencia as faixas; quem só vê a margem recebe os nomes
        boolean rates = currentActor.get().has(Permission.COST_RATE_MANAGE);
        return bands.findAllByOrderByNameAscValidFromAsc().stream()
                .map(b -> rates ? view(b, today) : withoutValue(view(b, today))).toList();
    }

    @Override
    public BandView createBand(CreateBandCommand command) {
        requireWrite(Permission.COST_RATE_MANAGE, "costing_bands");
        Guard.requireNonNull(command, "costing.band_required", "Informe a faixa");
        CostBand band = new CostBand(command.name(), command.hourlyCost(), command.validFrom());
        closePrevious(bands.findAllByNameForUpdate(band.getName()), band.getValidFrom(), "costing.band_overlap",
                "Já existe vigência desta faixa a partir desta data", previous -> {
                    bands.saveAndFlush(previous);
                    audit.record(AuditEntry.of("costing.band_ended", "cost_band", previous.getId())
                            .meta("name", band.getName()).change("validTo", null, previous.getValidTo())
                            .reason("nova vigência da faixa"));
                });
        bands.save(band);
        audit.record(AuditEntry.of("costing.band_created", "cost_band", band.getId())
                .created("name", band.getName()).created("hourlyCost", band.getHourlyCost())
                .created("validFrom", band.getValidFrom()));
        return view(band, today());
    }

    @Override
    public BandView endBand(UUID bandId, LocalDate validTo) {
        requireWrite(Permission.COST_RATE_MANAGE, "costing_bands");
        CostBand band = bands.findById(bandId).orElseThrow(() -> ResourceNotFoundException.of("cost_band", bandId));
        LocalDate before = band.getValidTo();
        band.endOn(validTo);
        bands.save(band);
        audit.record(AuditEntry.of("costing.band_ended", "cost_band", band.getId()).meta("name", band.getName())
                .change("validTo", before, band.getValidTo()));
        return view(band, today());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserBandView> userBands() {
        requireAny("costing_user_bands", Permission.COST_RATE_MANAGE);
        LocalDate today = today();
        Map<UUID, UserCostBand> current = new HashMap<>();
        userBands.findAllByOrderByUserIdAscValidFromAsc().stream().filter(b -> b.covers(today))
                .forEach(b -> current.put(b.getUserId(), b));
        return users.enabledUsers(null).stream()
                .sorted(Comparator.comparing(UserSummary::name, String.CASE_INSENSITIVE_ORDER))
                .map(u -> {
                    UserCostBand b = current.get(u.id());
                    return new UserBandView(u.id(), u.name(), u.enabled(), b == null ? null : b.getBandName(),
                            b == null ? null : b.getValidFrom(), b == null ? null : b.getValidTo());
                }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserBandView> userBandHistory(UUID userId) {
        requireAny("costing_user_bands", Permission.COST_RATE_MANAGE);
        UserSummary user = users.find(userId).orElseThrow(() -> ResourceNotFoundException.of("user", userId));
        return userBands.findAllByUserIdOrderByValidFromAsc(userId).stream()
                .map(b -> new UserBandView(user.id(), user.name(), user.enabled(), b.getBandName(), b.getValidFrom(),
                        b.getValidTo())).toList();
    }

    @Override
    public UserBandView assignUserBand(UUID userId, AssignUserBandCommand command) {
        requireWrite(Permission.COST_RATE_MANAGE, "costing_user_bands");
        Guard.requireNonNull(command, "costing.user_band_required", "Informe a faixa e a data");
        // a pessoa precisa existir nesta assessoria (o diretório só enxerga usuários da assessoria corrente)
        UserSummary user = users.find(userId).orElseThrow(() -> ResourceNotFoundException.of("user", userId));
        LocalDate from = Guard.requireNonNull(command.validFrom(), "costing.valid_from_required",
                "Informe o início da vigência");
        String bandName = command.bandName() == null || command.bandName().isBlank() ? null
                : CostBand.normalizeName(command.bandName());
        Guard.require(bandName == null || bands.existsByName(bandName), "costing.band_unknown",
                "Faixa de custo não cadastrada");
        List<UserCostBand> history = userBands.findAllByUserIdForUpdate(userId);
        Guard.requireState(history.stream().noneMatch(b -> b.getValidFrom().isAfter(from)),
                "costing.user_band_scheduled_later", "Já há faixa programada depois desta data para a pessoa");
        UserCostBand sameStart = history.stream().filter(b -> b.getValidFrom().equals(from)).findFirst().orElse(null);
        if (sameStart != null) {
            Guard.requireState(bandName != null, "costing.user_band_same_date",
                    "A faixa começa nesta data: informe outra faixa ou encerre a partir de outro dia");
            String before = sameStart.getBandName();
            sameStart.changeBand(bandName, today());
            userBands.save(sameStart);
            audit.record(AuditEntry.of("costing.user_band_changed", "user_cost_band", sameStart.getId())
                    .meta("userId", userId).change("bandName", before, sameStart.getBandName()));
            return new UserBandView(user.id(), user.name(), user.enabled(), sameStart.getBandName(),
                    sameStart.getValidFrom(), sameStart.getValidTo());
        }
        for (UserCostBand b : history) {
            if (b.overlaps(from, null)) {
                LocalDate before = b.getValidTo();
                b.endOn(from.minusDays(1));
                userBands.saveAndFlush(b);
                audit.record(AuditEntry.of("costing.user_band_ended", "user_cost_band", b.getId())
                        .meta("userId", userId).meta("bandName", b.getBandName())
                        .change("validTo", before, b.getValidTo()));
            }
        }
        if (bandName == null) {
            return new UserBandView(user.id(), user.name(), user.enabled(), null, null, null);
        }
        UserCostBand assigned = userBands.save(new UserCostBand(userId, bandName, from));
        audit.record(AuditEntry.of("costing.user_band_assigned", "user_cost_band", assigned.getId())
                .created("userId", userId).created("bandName", bandName).created("validFrom", from));
        return new UserBandView(user.id(), user.name(), user.enabled(), assigned.getBandName(),
                assigned.getValidFrom(), assigned.getValidTo());
    }

    // ================================================================== tarifas

    @Override
    @Transactional(readOnly = true)
    public List<TariffView> tariffs() {
        requireAny("costing_tariffs", Permission.COST_VIEW, Permission.COST_MANAGE);
        LocalDate today = today();
        return tariffs.findAllByOrderByChannelAscValidFromAsc().stream().map(t -> view(t, today)).toList();
    }

    @Override
    public TariffView createTariff(CreateTariffCommand command) {
        requireWrite(Permission.COST_MANAGE, "costing_tariffs");
        Guard.requireNonNull(command, "costing.tariff_required", "Informe a tarifa");
        ChannelTariff tariff = new ChannelTariff(command.channel(), command.unitCost(), command.validFrom());
        closePrevious(tariffs.findAllByChannelForUpdate(tariff.getChannel()), tariff.getValidFrom(),
                "costing.tariff_overlap", "Já existe tarifa deste canal a partir desta data",
                previous -> {
                    tariffs.saveAndFlush(previous);
                    audit.record(AuditEntry.of("costing.tariff_ended", "channel_tariff", previous.getId())
                            .meta("channel", tariff.getChannel()).change("validTo", null, previous.getValidTo())
                            .reason("nova vigência da tarifa"));
                });
        tariffs.save(tariff);
        audit.record(AuditEntry.of("costing.tariff_created", "channel_tariff", tariff.getId())
                .created("channel", tariff.getChannel()).created("unitCost", tariff.getUnitCost())
                .created("validFrom", tariff.getValidFrom()));
        return view(tariff, today());
    }

    @Override
    public TariffView endTariff(UUID tariffId, LocalDate validTo) {
        requireWrite(Permission.COST_MANAGE, "costing_tariffs");
        ChannelTariff tariff = tariffs.findById(tariffId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_tariff", tariffId));
        LocalDate before = tariff.getValidTo();
        tariff.endOn(validTo);
        tariffs.save(tariff);
        audit.record(AuditEntry.of("costing.tariff_ended", "channel_tariff", tariff.getId())
                .meta("channel", tariff.getChannel()).change("validTo", before, tariff.getValidTo()));
        return view(tariff, today());
    }

    // ================================================================== câmbio

    @Override
    @Transactional(readOnly = true)
    public List<FxRateView> fxRates() {
        requireAny("costing_fx_rates", Permission.COST_VIEW, Permission.COST_MANAGE);
        return fxRates.findAllByOrderByMonthAsc().stream().map(CostSettingsService::view).toList();
    }

    @Override
    public FxRateView saveFxRate(SaveFxRateCommand command) {
        requireWrite(Permission.COST_MANAGE, "costing_fx_rates");
        Guard.requireNonNull(command, "costing.fx_required", "Informe o câmbio");
        Guard.requireNonNull(command.month(), "costing.fx_month_required", "Informe o mês");
        FxRate existing = fxRates.findByMonth(command.month().atDay(1)).orElse(null);
        if (existing == null) {
            FxRate created = fxRates.save(new FxRate(command.month(), command.usdBrl()));
            audit.record(AuditEntry.of("costing.fx_rate_created", "fx_rate", created.getId())
                    .created("month", command.month()).created("usdBrl", created.getUsdBrl()));
            return view(created);
        }
        var before = existing.getUsdBrl();
        existing.change(command.usdBrl());
        fxRates.save(existing);
        audit.record(AuditEntry.of("costing.fx_rate_changed", "fx_rate", existing.getId())
                .meta("month", command.month()).change("usdBrl", before, existing.getUsdBrl()));
        return view(existing);
    }

    // ================================================================== custos fixos

    @Override
    @Transactional(readOnly = true)
    public List<OverheadView> overheads(YearMonth from, YearMonth to) {
        requireAny("costing_overheads", Permission.COST_VIEW, Permission.COST_MANAGE);
        List<Overhead> list = from == null && to == null ? overheads.findAllByOrderByMonthDescCreatedAtAsc()
                : overheads.findAllByMonthBetweenOrderByMonthAscCreatedAtAsc(
                        (from == null ? YearMonth.of(2000, 1) : from).atDay(1),
                        (to == null ? YearMonth.of(9999, 12) : to).atDay(1));
        boolean all = accessScope.hasAllCreditors();
        // escopo restrito não vê custos da assessoria nem de outros credores (desenho §7)
        List<Overhead> visible = list.stream().filter(o -> all
                || (o.getCreditorId() != null && accessScope.canAccessCreditor(o.getCreditorId()))).toList();
        Map<UUID, String> names = creditorNames(visible.stream().map(Overhead::getCreditorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return visible.stream().map(o -> view(o, o.getCreditorId() == null ? null : names.get(o.getCreditorId())))
                .toList();
    }

    @Override
    public OverheadView createOverhead(CreateOverheadCommand command) {
        Guard.requireNonNull(command, "costing.overhead_required", "Informe o custo fixo");
        requireOverheadWrite(command.allocation() == OverheadAllocation.CREDITOR ? command.creditorId() : null,
                command.allocation() == OverheadAllocation.CREDITOR);
        String creditorName = null;
        if (command.creditorId() != null) {
            creditorName = creditors.get(command.creditorId()).tradeName();
        }
        Overhead overhead = overheads.save(new Overhead(command.month(), command.description(), command.amount(),
                command.allocation(), command.creditorId()));
        audit.record(AuditEntry.of("costing.overhead_created", "overhead", overhead.getId())
                .creditor(overhead.getCreditorId()).created("month", overhead.getMonth())
                .created("description", overhead.getDescription()).created("amount", overhead.getAmount())
                .created("allocation", overhead.getAllocation()));
        refresh.reallocateOverheads(overhead.getMonth());
        return view(overhead, creditorName);
    }

    @Override
    public void deleteOverhead(UUID overheadId, String reason) {
        Overhead overhead = overheads.findById(overheadId)
                .orElseThrow(() -> ResourceNotFoundException.of("overhead", overheadId));
        requireOverheadWrite(overhead.getCreditorId(), overhead.getAllocation() == OverheadAllocation.CREDITOR);
        overheads.delete(overhead);
        audit.record(AuditEntry.of("costing.overhead_deleted", "overhead", overhead.getId())
                .creditor(overhead.getCreditorId()).change("month", overhead.getMonth(), null)
                .change("description", overhead.getDescription(), null).change("amount", overhead.getAmount(), null)
                .change("allocation", overhead.getAllocation(), null)
                .reason(reason == null || reason.isBlank() ? null : reason.trim()));
        refresh.reallocateOverheads(overhead.getMonth());
    }

    // ================================================================== apoio

    /**
     * Encerra na véspera a vigência em aberto que começou antes de {@code from}; qualquer outra vigência que alcance
     * {@code from} em diante é conflito.
     */
    private <T extends Dated> void closePrevious(List<T> existing, LocalDate from, String conflictCode,
                                                 String conflictMessage, java.util.function.Consumer<T> onClosed) {
        for (T current : existing) {
            if (!current.overlaps(from, null)) {
                continue;
            }
            Guard.requireState(current.getValidTo() == null && current.getValidFrom().isBefore(from), conflictCode,
                    conflictMessage);
            current.endOn(from.minusDays(1));
            onClosed.accept(current);
        }
    }

    private void requireOverheadWrite(UUID creditorId, boolean direct) {
        requireWrite(Permission.COST_MANAGE, null);
        if (direct) {
            Guard.requireNonNull(creditorId, "costing.overhead_creditor", "Informe o credor do custo direto");
            accessScope.requireCreditor(creditorId);
        } else {
            accessScope.requireAllCreditors("costing_overheads");
        }
    }

    /** Permissão de escrita e, para cadastros da assessoria ({@code resource} informado), todos os credores. */
    private void requireWrite(Permission permission, String resource) {
        accessScope.requirePermission(permission);
        if (resource != null) {
            accessScope.requireAllCreditors(resource);
        }
    }

    private void requireAny(String resource, Permission... permissions) {
        Actor actor = currentActor.get();
        if (Arrays.stream(permissions).noneMatch(actor::has)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("resource", resource,
                    "permission", Arrays.stream(permissions).map(Enum::name).collect(Collectors.joining("|")))));
            throw new ScopeAccessDeniedException();
        }
    }

    private Map<UUID, String> creditorNames(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return creditors.getAll(ids).stream().collect(Collectors.toMap(c -> c.id(), c -> c.tradeName(), (a, b) -> a));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static BandView view(CostBand b, LocalDate today) {
        return new BandView(b.getId(), b.getName(), b.getHourlyCost(), b.getValidFrom(), b.getValidTo(),
                b.covers(today));
    }

    private static BandView withoutValue(BandView v) {
        return new BandView(v.id(), v.name(), null, v.validFrom(), v.validTo(), v.current());
    }

    private static TariffView view(ChannelTariff t, LocalDate today) {
        return new TariffView(t.getId(), t.getChannel(), t.getUnitCost(), t.getValidFrom(), t.getValidTo(),
                t.covers(today));
    }

    private static FxRateView view(FxRate r) {
        return new FxRateView(r.getId(), r.getMonth().toString(), r.getUsdBrl());
    }

    private static OverheadView view(Overhead o, String creditorName) {
        return new OverheadView(o.getId(), o.getMonth().toString(), o.getDescription(), o.getAmount(),
                o.getAllocation(), o.getCreditorId(), creditorName);
    }
}
