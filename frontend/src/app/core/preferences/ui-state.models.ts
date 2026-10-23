/** Contrato de `UiStateController` (`/api/v1/me/ui-state`) do backend. */

export type ThemePreference = 'SYSTEM' | 'LIGHT' | 'DARK';
export type DensityPreference = 'COMFORTABLE' | 'COMPACT';
export type TourOutcome = 'COMPLETED' | 'DISMISSED';

export interface TourRecord {
  version: number;
  outcome: TourOutcome;
  recordedAt: string;
}

export interface ScreenRecord {
  version: number;
  seenAt: string;
}

export interface UiState {
  theme: ThemePreference;
  density: DensityPreference;
  navigationCollapsed: boolean;
  hintsEnabled: boolean;
  checklistDismissed: boolean;
  tours: Record<string, TourRecord>;
  screens: Record<string, ScreenRecord>;
  /** Item concluído → instante da conclusão. */
  checklist: Record<string, string>;
}

/** Alteração parcial: campos ausentes são mantidos; listas fazem "upsert" item a item. */
export interface UiStatePatch {
  theme?: ThemePreference;
  density?: DensityPreference;
  navigationCollapsed?: boolean;
  hintsEnabled?: boolean;
  checklistDismissed?: boolean;
  resetTours?: boolean;
  tours?: { tourId: string; version: number; outcome: TourOutcome }[];
  screens?: { screenKey: string; version: number }[];
  checklist?: { itemId: string; done: boolean }[];
}

export const DEFAULT_UI_STATE: UiState = {
  theme: 'SYSTEM',
  density: 'COMFORTABLE',
  navigationCollapsed: false,
  hintsEnabled: true,
  checklistDismissed: false,
  tours: {},
  screens: {},
  checklist: {},
};

/** Identificadores aceitos pelo servidor (tour, tela, item de checklist). */
export const UI_KEY_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)*$/;
