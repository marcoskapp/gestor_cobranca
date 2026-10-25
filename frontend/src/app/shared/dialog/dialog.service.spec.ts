import { Component, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NgbConfig } from '@ng-bootstrap/ng-bootstrap';
import { DIALOG_DATA, DialogRef } from './dialog-ref';
import { DIALOG_DIRECTIVES } from './dialog.directives';
import { DialogService } from './dialog.service';

@Component({
  selector: 'app-test-dialog',
  imports: [...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header"><h2 appDialogTitle>{{ data.title }}</h2></div>
    <div class="modal-footer">
      <button type="button" class="cancel" appDialogClose>Cancelar</button>
      <button type="button" class="ok" [appDialogClose]="'feito'">OK</button>
    </div>
  `,
})
class TestDialog {
  readonly data = inject<{ title: string }>(DIALOG_DATA);
  readonly ref = inject(DialogRef);
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('DialogService (NgbModal)', () => {
  let dialog: DialogService;

  beforeEach(() => {
    TestBed.inject(NgbConfig).animation = false;
    dialog = TestBed.inject(DialogService);
  });

  afterEach(() => dialog.closeAll());

  function windowEl(): HTMLElement {
    return document.querySelector('ngb-modal-window') as HTMLElement;
  }

  it('injeta os dados e liga o título ao aria-labelledby da janela', async () => {
    const ref = dialog.open(TestDialog, { data: { title: 'Excluir credor' } });
    await tick();
    const title = windowEl().querySelector('h2')!;
    expect(title.textContent).toContain('Excluir credor');
    expect(title.id).toBe(ref.titleId);
    expect(windowEl().getAttribute('aria-labelledby')).toBe(ref.titleId);
    expect(windowEl().getAttribute('role')).toBe('dialog');
  });

  it('afterClosed emite o valor do appDialogClose', async () => {
    const ref = dialog.open<TestDialog, { title: string }, string>(TestDialog, { data: { title: 'x' } });
    const results: (string | undefined)[] = [];
    ref.afterClosed().subscribe((r) => results.push(r));
    await tick();
    windowEl().querySelector<HTMLButtonElement>('.ok')!.click();
    await tick();
    expect(results).toEqual(['feito']);
  });

  it('appDialogClose sem valor e Esc cancelam com undefined (nunca string vazia)', async () => {
    const first = dialog.open(TestDialog, { data: { title: 'x' } });
    const results: unknown[] = [];
    first.afterClosed().subscribe((r) => results.push(r));
    await tick();
    windowEl().querySelector<HTMLButtonElement>('.cancel')!.click();
    await tick();

    const second = dialog.open(TestDialog, { data: { title: 'y' } });
    second.afterClosed().subscribe((r) => results.push(r));
    await tick();
    second.dismiss();
    await tick();
    expect(results).toEqual([undefined, undefined]);
  });

  it('disableClose usa fundo estático e alertdialog quando pedido', async () => {
    dialog.open(TestDialog, { data: { title: 'x' }, disableClose: true, role: 'alertdialog' });
    await tick();
    expect(windowEl().getAttribute('role')).toBe('alertdialog');
    expect(dialog.hasOpenDialogs()).toBe(true);
  });
});
