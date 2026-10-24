import { Injectable, signal } from '@angular/core';
import { SiteView } from '../data/portal.models';

/** Marca e textos do portal carregados pela moldura, compartilhados com as telas filhas. */
@Injectable()
export class PortalSite {
  readonly site = signal<SiteView | null>(null);

  set(site: SiteView | null): void {
    this.site.set(site);
  }
}
