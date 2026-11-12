import { FormControl, FormGroup } from '@angular/forms';
import { DistributionPreview, QueueView } from '../data/collection.models';
import { bandRows, tierRows } from './distribution-dialogs';
import { distributionValidator, STRATEGY_HINTS } from './queue-form';
import { autoDistributionText } from './queue-list';

describe('distribuição por nível de desempenho (filas)', () => {
  const group = (value: { strategy: string; maxCasesPerUser: number | null; autoEnabled: boolean; autoTimes: string[]; autoLimit: number | null }) =>
    new FormGroup({
      strategy: new FormControl(value.strategy),
      maxCasesPerUser: new FormControl(value.maxCasesPerUser),
      autoEnabled: new FormControl(value.autoEnabled),
      autoTimes: new FormControl(value.autoTimes),
      autoLimit: new FormControl(value.autoLimit),
    });

  it('exige a capacidade por operador na estratégia por nível', () => {
    expect(distributionValidator(group({ strategy: 'TIERED', maxCasesPerUser: null, autoEnabled: false, autoTimes: [], autoLimit: null }))).toEqual({
      tieredCapacity: true,
    });
    expect(distributionValidator(group({ strategy: 'TIERED', maxCasesPerUser: 30, autoEnabled: false, autoTimes: [], autoLimit: null }))).toBeNull();
  });

  it('distribuição automática ligada exige horário e limite e não vale para fila sob demanda', () => {
    expect(distributionValidator(group({ strategy: 'PULL', maxCasesPerUser: null, autoEnabled: true, autoTimes: [], autoLimit: null }))).toEqual({
      autoPull: true,
      autoTimes: true,
      autoLimit: true,
    });
    expect(
      distributionValidator(group({ strategy: 'ROUND_ROBIN', maxCasesPerUser: null, autoEnabled: true, autoTimes: ['08:00'], autoLimit: 200 })),
    ).toBeNull();
  });

  it('explica a estratégia por nível sem códigos internos', () => {
    expect(STRATEGY_HINTS.TIERED).toContain('valor esperado');
    expect(STRATEGY_HINTS.TIERED).not.toMatch(/TIERED|HIGH|LOW/);
  });

  it('resume a distribuição automática na lista de filas', () => {
    const queue = (auto: QueueView['autoDistribution']) => ({ autoDistribution: auto }) as QueueView;
    expect(autoDistributionText(queue({ enabled: true, times: ['08:00', '13:00'], limit: 200 }))).toBe('Automática às 08:00 e 13:00 (até 200 casos)');
    expect(autoDistributionText(queue({ enabled: true, times: ['09:30'], limit: null }))).toBe('Automática às 09:30');
    expect(autoDistributionText(queue({ enabled: false, times: ['08:00'], limit: 10 }))).toBeNull();
  });

  it('monta a prévia por nível com as faixas de potencial', () => {
    const preview: DistributionPreview = {
      queueId: 'q',
      strategy: 'TIERED',
      considered: 6,
      assigned: 6,
      unassigned: 0,
      byTierBand: [
        { tier: 'A', band: 'HIGH', cases: 2 },
        { tier: 'A', band: 'MEDIUM', cases: 1 },
        { tier: 'D', band: 'LOW', cases: 2 },
        { tier: 'D', band: 'DEVELOPMENT', cases: 1 },
      ],
      byTier: [
        { tier: 'A', members: 1, cases: 3 },
        { tier: 'D', members: 2, cases: 3 },
      ],
      members: [],
    };
    expect(tierRows(preview)).toEqual([
      { tier: 'A', members: 1, cases: 3, bands: [2, 1, 0, 0] },
      { tier: 'D', members: 2, cases: 3, bands: [0, 0, 2, 1] },
    ]);
  });

  it('sem visão do nível de todos, a prévia mostra só os totais por faixa', () => {
    const preview: DistributionPreview = {
      queueId: 'q',
      strategy: 'TIERED',
      considered: 5,
      assigned: 5,
      unassigned: 0,
      byTierBand: [
        { tier: null, band: 'HIGH', cases: 2 },
        { tier: null, band: 'LOW', cases: 3 },
      ],
      byTier: [],
      members: [],
    };
    expect(tierRows(preview)).toEqual([]);
    expect(bandRows(preview)).toEqual([
      { band: 'HIGH', cases: 2 },
      { band: 'LOW', cases: 3 },
    ]);
  });

  it('com visão do nível de todos, não monta a prévia por faixa', () => {
    const preview = {
      byTierBand: [{ tier: 'A', band: 'HIGH', cases: 2 }],
      byTier: [{ tier: 'A', members: 1, cases: 2 }],
    } as DistributionPreview;
    expect(bandRows(preview)).toEqual([]);
  });
});
