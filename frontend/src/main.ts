import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { applyCachedAppearance } from './app/core/preferences/theme.service';

// aplica o tema guardado antes de renderizar, sem "piscar" (CSP não permite script inline)
applyCachedAppearance();

bootstrapApplication(App, appConfig).catch(() => {
  // falha de inicialização: mensagem mínima, sem detalhes internos
  document.body.textContent = 'Não foi possível iniciar a aplicação. Recarregue a página.';
});
