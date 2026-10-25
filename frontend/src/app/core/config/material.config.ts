import { EnvironmentProviders, inject, makeEnvironmentProviders, provideEnvironmentInitializer } from '@angular/core';
import { provideDateFnsAdapter } from '@angular/material-date-fns-adapter';
import { MAT_DATE_LOCALE, MatDateFormats } from '@angular/material/core';
import { MAT_DIALOG_DEFAULT_OPTIONS, MatDialog, MatDialogConfig } from '@angular/material/dialog';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS, MatFormFieldDefaultOptions } from '@angular/material/form-field';
import { MatIconRegistry } from '@angular/material/icon';
import { ptBR } from 'date-fns/locale';

const DATE_FORMATS: MatDateFormats = {
  parse: { dateInput: ['dd/MM/yyyy', 'ddMMyyyy', 'd/M/yyyy'] },
  display: {
    dateInput: 'dd/MM/yyyy',
    monthYearLabel: 'MMM yyyy',
    dateA11yLabel: 'PPP',
    monthYearA11yLabel: 'MMMM yyyy',
  },
};

/** Datepicker do Angular Material em pt-BR (date-fns), formato dd/MM/yyyy. TRANSITÓRIO. */
export function provideMaterialDateLocale(): EnvironmentProviders {
  return makeEnvironmentProviders([{ provide: MAT_DATE_LOCALE, useValue: ptBR }, provideDateFnsAdapter(DATE_FORMATS)]);
}

/**
 * TRANSITÓRIO — padrões dos componentes Angular Material usados pelas telas ainda não migradas
 * para o Bootstrap. Fornecido no injetor das rotas do shell e do portal (não na raiz), para que
 * o Material fique fora do bundle inicial. O `MatDialog` é fornecido aqui para enxergar
 * `MAT_DIALOG_DEFAULT_OPTIONS` deste injetor. Remover ao fim da migração.
 */
export function provideMaterialDefaults(): EnvironmentProviders {
  return makeEnvironmentProviders([
    MatDialog,
    {
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: { appearance: 'outline', subscriptSizing: 'dynamic' } satisfies MatFormFieldDefaultOptions,
    },
    {
      provide: MAT_DIALOG_DEFAULT_OPTIONS,
      useValue: {
        autoFocus: 'first-tabbable',
        restoreFocus: true,
        hasBackdrop: true,
        width: '560px',
        maxWidth: 'calc(100vw - 32px)',
      } satisfies MatDialogConfig,
    },
    provideMaterialDateLocale(),
    provideEnvironmentInitializer(() => {
      inject(MatIconRegistry).setDefaultFontSetClass('material-icons-outlined');
    }),
  ]);
}
