import { EnvironmentProviders, inject, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import {
  NgbDropdownConfig,
  NgbModalConfig,
  NgbPaginationConfig,
  NgbTooltipConfig,
  NgbTypeaheadConfig,
} from '@ng-bootstrap/ng-bootstrap';

/**
 * Padrões do ng-bootstrap para toda a aplicação: diálogos acessíveis (Esc fecha, foco devolvido),
 * tooltips e menus anexados ao `body` (não são cortados por contêineres com `overflow`), paginação com primeira/última
 * página. Os rótulos acessíveis do ng-bootstrap são traduzidos em `ngb-translations.ts`. As datas
 * (modelo ISO, pt-BR) ficam em `provideDatepickerDefaults()` (datepicker.config.ts), fornecido nas
 * rotas do shell e do portal para manter o datepicker fora do bundle inicial.
 */
export function provideBootstrapDefaults(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideAppInitializer(() => {
      const modal = inject(NgbModalConfig);
      modal.backdrop = true;
      modal.keyboard = true;
      modal.scrollable = true;

      const tooltip = inject(NgbTooltipConfig);
      tooltip.container = 'body';
      tooltip.openDelay = 300;

      inject(NgbDropdownConfig).container = 'body';

      const pagination = inject(NgbPaginationConfig);
      pagination.boundaryLinks = true;
      pagination.maxSize = 5;
      pagination.rotate = true;
      pagination.ellipses = false;
      pagination.size = 'sm';

      inject(NgbTypeaheadConfig).focusFirst = false;
    }),
  ]);
}
