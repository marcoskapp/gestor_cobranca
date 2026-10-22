import { EnvironmentProviders, inject, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import { MAT_DIALOG_DEFAULT_OPTIONS, MatDialogConfig } from '@angular/material/dialog';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS, MatFormFieldDefaultOptions } from '@angular/material/form-field';
import { MatIconRegistry } from '@angular/material/icon';
import { MAT_SNACK_BAR_DEFAULT_OPTIONS, MatSnackBarConfig } from '@angular/material/snack-bar';

/** Padrões visuais dos componentes Material usados em toda a aplicação. */
export function provideMaterialDefaults(): EnvironmentProviders {
  return makeEnvironmentProviders([
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
    {
      provide: MAT_SNACK_BAR_DEFAULT_OPTIONS,
      useValue: { duration: 5000, horizontalPosition: 'end', verticalPosition: 'bottom' } satisfies MatSnackBarConfig,
    },
    provideAppInitializer(() => {
      inject(MatIconRegistry).setDefaultFontSetClass('material-icons-outlined');
    }),
  ]);
}
