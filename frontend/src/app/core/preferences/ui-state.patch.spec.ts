import { DEFAULT_UI_STATE, UiState } from './ui-state.models';
import { applyPatch, isEmptyPatch, mergePatches } from './ui-state.patch';

const NOW = '2026-10-22T12:00:00.000Z';

function state(overrides: Partial<UiState> = {}): UiState {
  return { ...DEFAULT_UI_STATE, ...overrides };
}

describe('ui-state.patch', () => {
  it('aplica preferências e upserts sem regredir versões', () => {
    const base = state({
      tours: { 'admin.users': { version: 2, outcome: 'COMPLETED', recordedAt: 'antes' } },
      screens: { 'admin.users': { version: 3, seenAt: 'antes' } },
    });
    const next = applyPatch(
      base,
      {
        theme: 'DARK',
        tours: [
          { tourId: 'admin.users', version: 1, outcome: 'DISMISSED' },
          { tourId: 'app.welcome', version: 1, outcome: 'COMPLETED' },
        ],
        screens: [
          { screenKey: 'admin.users', version: 2 },
          { screenKey: 'admin.roles', version: 1 },
        ],
        checklist: [{ itemId: 'admin.creditor', done: true }],
      },
      NOW,
    );

    expect(next.theme).toBe('DARK');
    expect(next.tours['admin.users']).toEqual({ version: 2, outcome: 'COMPLETED', recordedAt: 'antes' });
    expect(next.tours['app.welcome'].recordedAt).toBe(NOW);
    expect(next.screens['admin.users'].version).toBe(3);
    expect(next.screens['admin.roles'].version).toBe(1);
    expect(next.checklist['admin.creditor']).toBe(NOW);
    // imutável: o estado original não muda
    expect(base.theme).toBe('SYSTEM');
    expect(base.screens['admin.roles']).toBeUndefined();
  });

  it('resetTours apaga os tours antes de aplicar os do mesmo patch', () => {
    const base = state({ tours: { a: { version: 1, outcome: 'COMPLETED', recordedAt: 'x' } } });
    const next = applyPatch(base, { resetTours: true, tours: [{ tourId: 'b', version: 1, outcome: 'DISMISSED' }] }, NOW);
    expect(Object.keys(next.tours)).toEqual(['b']);
  });

  it('desmarcar item do checklist remove a conclusão', () => {
    const next = applyPatch(state({ checklist: { a: 'x' } }), { checklist: [{ itemId: 'a', done: false }] });
    expect(next.checklist).toEqual({});
  });

  it('combina patches pendentes: escalares do último, listas por chave', () => {
    const merged = mergePatches(
      { theme: 'DARK', screens: [{ screenKey: 'a', version: 1 }], checklist: [{ itemId: 'x', done: true }] },
      { theme: 'LIGHT', density: 'COMPACT', screens: [{ screenKey: 'a', version: 2 }, { screenKey: 'b', version: 1 }] },
    );
    expect(merged).toEqual({
      theme: 'LIGHT',
      density: 'COMPACT',
      screens: [
        { screenKey: 'a', version: 2 },
        { screenKey: 'b', version: 1 },
      ],
      checklist: [{ itemId: 'x', done: true }],
    });
  });

  it('um reset posterior descarta tours anteriores ainda não enviados', () => {
    const merged = mergePatches(
      { tours: [{ tourId: 'a', version: 1, outcome: 'COMPLETED' }] },
      { resetTours: true },
    );
    expect(merged).toEqual({ resetTours: true });
  });

  it('identifica patch vazio', () => {
    expect(isEmptyPatch({})).toBe(true);
    expect(isEmptyPatch({ screens: [] })).toBe(true);
    expect(isEmptyPatch({ hintsEnabled: false })).toBe(false);
  });
});
