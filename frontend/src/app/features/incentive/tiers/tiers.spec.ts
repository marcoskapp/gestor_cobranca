import { of } from 'rxjs';
import { button, render, setup, text, tick } from '../../../../testing/component-harness';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { MyTierCard, myTierSummary } from './my-tier-card';
import { TierMatrixTable } from './tier-matrix';
import { MyTier, TierPerson, TierRoundDetail, TierRoundSummary } from './tier.models';
import { STANDARD_MATRIX, cloneMatrix, isValidMatrix, quartileText, settingsErrors, tierExplanation } from './tier.rules';
import { TierPage, tierTabs } from './tier-page';

const confirm = { confirm: vi.fn() };
const dialog = { open: vi.fn() };

const round = (over: Partial<TierRoundSummary> = {}): TierRoundSummary => ({
  id: 'r-1',
  periodFrom: '2026-08-14',
  periodTo: '2026-11-11',
  status: 'PROPOSED',
  origin: 'MANUAL',
  reviewDeadline: '2026-11-17',
  createdAt: '2026-11-12T12:00:00Z',
  publishedAt: null,
  validFrom: null,
  expiredAt: null,
  people: 2,
  tierCounts: { A: 0, B: 1, C: 1, D: 0 },
  adjusted: 0,
  ...over,
});

const person = (over: Partial<TierPerson> = {}): TierPerson => ({
  userId: 'u-x',
  name: 'Operador X',
  qualityQuartile: 2,
  efficiencyQuartile: 2,
  qualityGateFailed: false,
  evaluations: 6,
  qualityAverage: 7.5,
  cases: 40,
  recoveryRatio: 1.2,
  proposedTier: 'C',
  finalTier: 'C',
  adjusted: false,
  adjustedByName: null,
  adjustedAt: null,
  adjustmentReason: null,
  self: false,
  ...over,
});

const detail = (canPublish: boolean, people: TierPerson[]): TierRoundDetail => ({
  round: round(),
  settings: { windowDays: 90, minEvaluations: 5, minCases: 30, qualityGate: 7, matrix: STANDARD_MATRIX, defaultTier: 'B', periodicity: 'MONTHLY', reviewBusinessDays: 3, customized: false },
  people,
  canAdjust: true,
  canPublish,
});

const mine = (over: Partial<MyTier> = {}): MyTier => ({
  status: 'CLASSIFIED',
  tier: 'B',
  defaultTier: 'B',
  validFrom: '2026-11-12',
  periodFrom: '2026-08-14',
  periodTo: '2026-11-11',
  qualityQuartile: 4,
  efficiencyQuartile: 2,
  qualityGateFailed: false,
  missingQuality: false,
  missingEfficiency: false,
  evaluations: 8,
  cases: 40,
  minEvaluations: 5,
  minCases: 30,
  qualityGate: 7,
  adjusted: false,
  matrix: STANDARD_MATRIX,
  ...over,
});

describe('Classificação de desempenho', () => {
  afterEach(() => vi.clearAllMocks());

  it('regras: matriz com 16 células de A a D e limites dos parâmetros iguais aos do servidor', () => {
    expect(isValidMatrix(STANDARD_MATRIX)).toBe(true);
    const invalid = cloneMatrix(STANDARD_MATRIX) as unknown as Record<string, Record<string, string>>;
    invalid['Q1']['Q1'] = 'E';
    expect(isValidMatrix(invalid)).toBe(false);
    const missing = cloneMatrix(STANDARD_MATRIX) as unknown as Record<string, Record<string, string>>;
    delete missing['Q2']['Q3'];
    expect(isValidMatrix(missing)).toBe(false);
    const ok = { windowDays: 90, minEvaluations: 5, minCases: 30, qualityGate: 7, matrix: STANDARD_MATRIX, defaultTier: 'B' as const, periodicity: 'MONTHLY' as const, reviewBusinessDays: 3 };
    expect(settingsErrors(ok)).toEqual([]);
    expect(settingsErrors({ ...ok, windowDays: 29, qualityGate: 10.5, reviewBusinessDays: 0 })).toHaveLength(3);
    expect(settingsErrors({ ...ok, qualityGate: 7.005 })).toEqual(['O portão de qualidade vai de 0 a 10, com até 2 casas.']);
  });

  it('regras: explicação em linguagem simples, sem posição', () => {
    expect(quartileText(4)).toBe('Q4 · entre os 25% melhores');
    expect(quartileText(null)).toBe('Sem amostra suficiente');
    expect(tierExplanation(person({ qualityQuartile: 2, efficiencyQuartile: 4, qualityGateFailed: true, proposedTier: 'B' }), 'B')).toContain('limitada a Q2');
    expect(tierExplanation(person({ qualityQuartile: null }), 'C')).toBe('Sem amostra suficiente na janela: vale o nível padrão C.');
  });

  it('meu nível: quartis, falta de amostra e ajuste explicados', () => {
    expect(myTierSummary(mine({ status: 'NONE', tier: 'B' })).headline).toBe('Nível B (padrão)');
    const classified = myTierSummary(mine());
    expect(classified.headline).toBe('Nível B');
    expect(classified.lines).toContain('Qualidade (monitoria): Q4 · entre os 25% melhores.');
    const missing = myTierSummary(mine({ missingEfficiency: true, efficiencyQuartile: null, cases: 12, adjusted: true }));
    expect(missing.lines.join(' ')).toContain('12 de 30 casos mínimos');
    expect(missing.lines.join(' ')).toContain('ajustado na revisão');
  });

  it('supervisor: revê a equipe e ajusta (nunca a si), sem publicar nem parâmetros', async () => {
    const { http } = setup(TierPage, ['TIER_REVIEW', 'PERFORMANCE_VIEW_TEAM'], 'u-s', [
      { provide: ConfirmService, useValue: confirm },
      { provide: DialogService, useValue: dialog },
    ]);
    const fixture = await render(TierPage, http, (url) => {
      if (url.endsWith('/incentive/tiers/rounds')) return [round()];
      if (url.endsWith('/incentive/tiers/rounds/r-1'))
        return detail(false, [person(), person({ userId: 'u-s', name: 'Supervisor S', self: true, qualityQuartile: null, efficiencyQuartile: null, proposedTier: 'B', finalTier: 'B' })]);
      return undefined;
    });
    const t = text(fixture);
    expect(t).toContain('Operador X');
    expect(t).toContain('Em revisão');
    expect(t).toContain('a publicação é da rodada inteira');
    expect(button(fixture, 'Parâmetros')).toBeUndefined();
    expect(button(fixture, 'Publicar')).toBeUndefined();
    expect(button(fixture, 'Apurar agora')).toBeUndefined();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('button[aria-label="Ajustar o nível de Operador X"]')).not.toBeNull();
    expect(el.querySelector('button[aria-label="Ajustar o nível de Supervisor S"]')).toBeNull();
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    el.querySelector<HTMLButtonElement>('button[aria-label="Ajustar o nível de Operador X"]')!.click();
    expect(dialog.open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: expect.objectContaining({ roundId: 'r-1', defaultTier: 'B' }) }));
  });

  it('gestão: publica com confirmação forte', async () => {
    const { http, notifications } = setup(TierPage, ['TIER_REVIEW', 'TIER_MANAGE', 'PERFORMANCE_VIEW_ALL'], 'u-g', [
      { provide: ConfirmService, useValue: confirm },
      { provide: DialogService, useValue: dialog },
    ]);
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(TierPage, http, (url) => {
      if (url.endsWith('/incentive/tiers/rounds')) return [round()];
      if (url.endsWith('/incentive/tiers/rounds/r-1')) return detail(true, [person()]);
      if (url.endsWith('/incentive/tiers/settings')) return detail(true, []).settings;
      return undefined;
    });
    expect(button(fixture, 'Parâmetros')).toBeDefined();
    expect(button(fixture, 'Apurar agora')!.disabled).toBe(true);
    button(fixture, 'Publicar')!.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ confirmationText: 'PUBLICAR' }));
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/incentive/tiers/rounds/r-1/publish')).flush(round({ status: 'PUBLISHED' }));
    await tick();
    expect(notifications.success[0]).toContain('publicada');
  });

  it('abas: "Rodadas" só com escopo de equipe ou de todos; só parâmetros abre em "Parâmetros"', async () => {
    expect(tierTabs(true, true)).toEqual(['rodadas', 'parametros']);
    expect(tierTabs(true, false)).toEqual(['rodadas']);
    expect(tierTabs(false, true)).toEqual(['parametros']);
    expect(tierTabs(false, false)).toEqual([]);

    const { http } = setup(TierPage, ['TIER_MANAGE'], 'u-p', [
      { provide: ConfirmService, useValue: confirm },
      { provide: DialogService, useValue: dialog },
    ]);
    const requested: string[] = [];
    const fixture = await render(TierPage, http, (url) => {
      requested.push(url);
      if (url.endsWith('/incentive/tiers/settings')) return detail(true, []).settings;
      return undefined;
    });
    expect(button(fixture, 'Rodadas')).toBeUndefined();
    expect(button(fixture, 'Apurar agora')).toBeUndefined();
    expect(button(fixture, 'Parâmetros')).toBeDefined();
    expect(text(fixture)).toContain('Matriz');
    expect(requested.some((u) => u.includes('/incentive/tiers/rounds'))).toBe(false);
  });

  it('matriz editável: cada célula tem rótulo de linha e coluna e emite a matriz nova', async () => {
    const { http } = setup(TierMatrixTable, []);
    const fixture = await render(TierMatrixTable, http, () => undefined, { matrix: STANDARD_MATRIX, editable: true, idPrefix: 'm' });
    const selects = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>('select');
    expect(selects).toHaveLength(16);
    expect(selects[0].getAttribute('aria-labelledby')).toBe('m-q-Q4 m-e-Q4');
    let emitted: unknown;
    fixture.componentInstance.matrixChange.subscribe((m) => (emitted = m));
    selects[15].value = 'A';
    selects[15].dispatchEvent(new Event('change'));
    expect((emitted as typeof STANDARD_MATRIX).Q1.Q1).toBe('A');
    expect(STANDARD_MATRIX.Q1.Q1).toBe('D');
  });

  it('cartão "Seu nível de distribuição" mostra o nível e a base', async () => {
    const { http } = setup(MyTierCard, ['PERFORMANCE_VIEW_OWN', 'CASE_WORK']);
    const fixture = await render(MyTierCard, http, (url) => (url.endsWith('/incentive/tiers/me') ? mine({ qualityGateFailed: true, qualityQuartile: 2 }) : undefined));
    const t = text(fixture);
    expect(t).toContain('Seu nível de distribuição');
    expect(t).toContain('Nível B');
    expect(t).toContain('Vale desde 12/11/2026');
    expect(t).toContain('no máximo como Q2');
  });
});
