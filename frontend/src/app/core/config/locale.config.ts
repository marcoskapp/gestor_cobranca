import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { provideAppLocale } from './app-locale';
import { provideMaterialDateLocale } from './material.config';

export { APP_LOCALE, provideAppLocale } from './app-locale';

/**
 * Locale pt-BR + datepicker do Angular Material em português. TRANSITÓRIO: usado pelos testes
 * das telas ainda em Material. A aplicação usa `provideAppLocale()` (app-locale.ts) na raiz e o
 * datepicker do Material só no injetor das rotas (`provideMaterialDefaults`), para manter o
 * Material fora do bundle inicial. Ao fim da migração, remover este arquivo.
 */
export function provideLocale(): EnvironmentProviders {
  return makeEnvironmentProviders([provideAppLocale(), provideMaterialDateLocale()]);
}
