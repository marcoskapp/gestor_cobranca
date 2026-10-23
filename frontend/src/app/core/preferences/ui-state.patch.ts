import { UiState, UiStatePatch } from './ui-state.models';

/**
 * Funções puras de alteração do estado de interface, espelho das regras de `UserUiState` no
 * backend: versões de tour e de tela nunca regridem; `resetTours` vale antes dos tours do
 * mesmo patch.
 */

function upsert<T, K extends keyof T>(base: readonly T[] | undefined, next: readonly T[] | undefined, key: K): T[] | undefined {
  if (!base && !next) {
    return undefined;
  }
  const merged = new Map<T[K], T>();
  for (const item of [...(base ?? []), ...(next ?? [])]) {
    merged.set(item[key], item);
  }
  return [...merged.values()];
}

/** Combina dois patches pendentes em um só, preservando a ordem de aplicação. */
export function mergePatches(first: UiStatePatch, second: UiStatePatch): UiStatePatch {
  const merged: UiStatePatch = { ...first, ...stripLists(second) };
  merged.resetTours = first.resetTours || second.resetTours || undefined;
  merged.tours = second.resetTours ? second.tours : upsert(first.tours, second.tours, 'tourId');
  merged.screens = upsert(first.screens, second.screens, 'screenKey');
  merged.checklist = upsert(first.checklist, second.checklist, 'itemId');
  return clean(merged);
}

/** Aplica um patch localmente (atualização otimista). */
export function applyPatch(state: UiState, patch: UiStatePatch, now: string = new Date().toISOString()): UiState {
  const next: UiState = {
    ...state,
    tours: patch.resetTours ? {} : { ...state.tours },
    screens: { ...state.screens },
    checklist: { ...state.checklist },
  };
  if (patch.theme) next.theme = patch.theme;
  if (patch.density) next.density = patch.density;
  if (patch.navigationCollapsed !== undefined) next.navigationCollapsed = patch.navigationCollapsed;
  if (patch.hintsEnabled !== undefined) next.hintsEnabled = patch.hintsEnabled;
  if (patch.checklistDismissed !== undefined) next.checklistDismissed = patch.checklistDismissed;

  for (const tour of patch.tours ?? []) {
    const current = next.tours[tour.tourId];
    if (current && (current.version > tour.version || (current.version === tour.version && current.outcome === tour.outcome))) {
      continue;
    }
    next.tours[tour.tourId] = { version: tour.version, outcome: tour.outcome, recordedAt: now };
  }
  for (const screen of patch.screens ?? []) {
    const current = next.screens[screen.screenKey];
    if (!current || current.version < screen.version) {
      next.screens[screen.screenKey] = { version: screen.version, seenAt: now };
    }
  }
  for (const item of patch.checklist ?? []) {
    if (!item.done) {
      delete next.checklist[item.itemId];
    } else if (!next.checklist[item.itemId]) {
      next.checklist[item.itemId] = now;
    }
  }
  return next;
}

export function isEmptyPatch(patch: UiStatePatch): boolean {
  return Object.keys(clean({ ...patch })).length === 0;
}

function stripLists(patch: UiStatePatch): UiStatePatch {
  const { tours: _t, screens: _s, checklist: _c, resetTours: _r, ...scalars } = patch;
  return scalars;
}

function clean(patch: UiStatePatch): UiStatePatch {
  const result = patch as Record<string, unknown>;
  for (const key of Object.keys(result)) {
    const value = result[key];
    if (value === undefined || (Array.isArray(value) && value.length === 0)) {
      delete result[key];
    }
  }
  return patch;
}
