import { loadTranslations } from '@angular/localize';
import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { NGB_TRANSLATIONS_PT_BR } from './app/core/config/ngb-translations';
import { applyCachedAppearance } from './app/core/preferences/theme.service';

// aplica o tema guardado antes de renderizar, sem "piscar" (CSP não permite script inline)
applyCachedAppearance();

// rótulos acessíveis do ng-bootstrap em português ($localize em tempo de execução)
loadTranslations(NGB_TRANSLATIONS_PT_BR);

bootstrapApplication(App, appConfig).catch(() => {
  // falha de inicialização: mensagem mínima, sem detalhes internos
  document.body.textContent = 'Não foi possível iniciar a aplicação. Recarregue a página.';
});
