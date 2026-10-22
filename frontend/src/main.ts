import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';

bootstrapApplication(App, appConfig).catch(() => {
  // falha de inicialização: mensagem mínima, sem detalhes internos
  document.body.textContent = 'Não foi possível iniciar a aplicação. Recarregue a página.';
});
