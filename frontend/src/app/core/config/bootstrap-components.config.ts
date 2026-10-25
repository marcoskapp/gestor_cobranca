import { EnvironmentProviders, inject, makeEnvironmentProviders, provideEnvironmentInitializer } from '@angular/core';
import {
  NgbDatepickerConfig,
  NgbDropdownConfig,
  NgbInputDatepickerConfig,
  NgbPaginationConfig,
  NgbTooltipConfig,
  NgbTypeaheadConfig,
} from '@ng-bootstrap/ng-bootstrap';

/**
 * Padrões dos componentes de tela do ng-bootstrap (tooltips, menus, paginação, calendários e
 * autocompletar), aplicados quando a área autenticada ou o portal é carregado (providers das rotas
 * `SHELL_ROUTES` e `PORTAL_ROUTES`). Fica num arquivo próprio (não em `bootstrap.config.ts`, que a
 * raiz importa): o empacotador separa por arquivo, e as configurações abaixo trariam o código desses
 * componentes para o bundle inicial. As configurações são singletons da raiz (`providedIn: 'root'`):
 * o inicializador do injetor da rota altera a mesma instância que os diálogos abertos pelo
 * `DialogService` (injetor raiz) enxergam. Tooltips, menus e calendários anexados ao `body` não
 * são cortados por contêineres com `overflow`; paginação com primeira/última página.
 */
export function provideBootstrapComponentDefaults(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideEnvironmentInitializer(() => {
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

      // o datepicker em campo (`input[ngbDatepicker]`) tem configuração própria (NgbInputDatepickerConfig,
      // outra instância): os mesmos padrões valem para os dois
      for (const datepicker of [inject(NgbDatepickerConfig), inject(NgbInputDatepickerConfig)]) {
        datepicker.firstDayOfWeek = 7; // domingo, como no calendário brasileiro
        datepicker.minDate = { year: 1900, month: 1, day: 1 };
        datepicker.maxDate = { year: 2100, month: 12, day: 31 };
      }
      const inputDatepicker = inject(NgbInputDatepickerConfig);
      // calendário do campo anexado ao body (como menus e tooltips), em posição fixa: não é cortado
      // pela rolagem de diálogos (`modal-dialog-scrollable`), tabelas e painéis com `overflow`
      inputDatepicker.container = 'body';
      inputDatepicker.popperOptions = (options) => ({ ...options, strategy: 'fixed' });

      inject(NgbTypeaheadConfig).focusFirst = false;
    }),
  ]);
}
