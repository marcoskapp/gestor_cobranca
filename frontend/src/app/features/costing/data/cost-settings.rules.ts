import { formatAmount, toAmount } from '../../../shared/components/amount/amount';
import { CHANNEL_ITEMS, ChannelInvoice, ChannelItem, CostBand, ItemEstimate, Money } from './costing.models';
import { monthLabel } from './margin.rules';

/** Regras puras da configuração de custos (vigências, meses e faixas). */

export interface Validity {
  validFrom: string;
  validTo: string | null;
}

/** Em vigor em `today` (`AAAA-MM-DD`): começou e não terminou (o fim é inclusivo). */
export function isCurrent(item: Validity, today: string): boolean {
  return item.validFrom <= today && (item.validTo === null || item.validTo >= today);
}

/** Ainda pode ser encerrada: sem fim ou com fim no futuro. */
export function isOpen(item: Validity, today: string): boolean {
  return item.validTo === null || item.validTo >= today;
}

/** "a partir de 01/10/2026", "de 01/01/2026 a 30/09/2026". */
export function validityText(item: Validity): string {
  const br = (iso: string) => {
    const [y, m, d] = iso.split('-');
    return `${d}/${m}/${y}`;
  };
  return item.validTo ? `de ${br(item.validFrom)} a ${br(item.validTo)}` : `a partir de ${br(item.validFrom)}`;
}

/** Vigências agrupadas pelo nome (A→Z) e, dentro do nome, da mais recente para a mais antiga. */
export function sortBands(bands: readonly CostBand[]): CostBand[] {
  return [...bands].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR') || b.validFrom.localeCompare(a.validFrom));
}

/** Nomes de faixa que podem receber pessoas (alguma vigência aberta), sem repetição, A→Z. */
export function assignableBandNames(bands: readonly CostBand[], today: string): string[] {
  return [...new Set(bands.filter((b) => isOpen(b, today)).map((b) => b.name))].sort((a, b) => a.localeCompare(b, 'pt-BR'));
}

/** Primeiro dia do mês (`AAAA-MM-01`). */
export function monthStart(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-01`;
}

/** Meses para escolha (do mais recente ao mais antigo): `forward` meses à frente e `back` meses para trás. */
export function monthOptions(today: Date, back = 24, forward = 1): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  for (let i = forward; i >= -back; i--) {
    const value = monthStart(new Date(today.getFullYear(), today.getMonth() + i, 1));
    out.push({ value, label: monthLabel(value) });
  }
  return out;
}

/** Mês fechado mais recente (padrão do câmbio e dos custos fixos). */
export function lastClosedMonthStart(today: Date): string {
  return monthStart(new Date(today.getFullYear(), today.getMonth() - 1, 1));
}

/** Normaliza o mês do servidor (`AAAA-MM` ou `AAAA-MM-DD`) para o primeiro dia. */
export function normalizeMonth(month: string): string {
  const match = /^(\d{4})-(\d{2})/.exec(month);
  return match ? `${match[1]}-${match[2]}-01` : month;
}

// ------------------------------------------------------------------ faturas de canal (fase 2, §13)

const QUANTITY = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });
const USD = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 });

/** Uso do canal no mês na unidade cobrada: "120 minutos", "35 conversas ou mensagens", "US$ 12,40". */
export function usageText(estimate: Pick<ItemEstimate, 'quantity' | 'unit' | 'item'> | null): string {
  const n = toAmount(estimate?.quantity ?? 0) ?? 0;
  if (!estimate || n === 0) {
    return 'sem uso';
  }
  switch (estimate.unit) {
    case 'MINUTES':
      return `${QUANTITY.format(n)} ${n === 1 ? 'minuto' : 'minutos'}`;
    case 'USD':
      return `${USD.format(n)} de uso`;
    default:
      if (estimate.item === 'WHATSAPP') {
        return `${QUANTITY.format(n)} ${n === 1 ? 'conversa' : 'conversas'}`;
      }
      return `${QUANTITY.format(n)} ${n === 1 ? 'mensagem' : 'mensagens'}`;
  }
}

/** Diferença entre a fatura e a estimativa com o sinal escrito ("+R$ 3,00", "−R$ 1,20", "R$ 0,00"). */
export function differenceText(invoice: Money, estimated: Money): string {
  const diff = Math.round(((toAmount(invoice) ?? 0) - (toAmount(estimated) ?? 0)) * 100) / 100;
  if (diff === 0) {
    return formatAmount(0);
  }
  return diff < 0 ? `−${formatAmount(-diff)}` : `+${formatAmount(diff)}`;
}

/** Texto ao lado do valor no diálogo: a estimativa do mês e a diferença que será rateada. */
export function invoiceEstimateText(estimate: ItemEstimate | null, amount: number | null): string {
  if (!estimate) {
    return 'Estimativa do mês: sem uso registrado deste canal. Sem uso, o valor da fatura fica em "Sem credor" no painel.';
  }
  const base = `Estimativa do mês: ${formatAmount(estimate.estimated)} (${usageText(estimate)}).`;
  if (amount === null) {
    return base;
  }
  if ((toAmount(estimate.quantity) ?? 0) === 0 && (toAmount(estimate.estimated) ?? 0) === 0) {
    return `${base} Sem uso, o valor da fatura fica em "Sem credor" no painel.`;
  }
  return `${base} Diferença de ${differenceText(amount, estimate.estimated)}, rateada pelos credores e carteiras na proporção do custo estimado.`;
}

/** Linha da aba Faturas: um canal no mês, com a estimativa e a fatura (se houver). */
export interface InvoiceRow {
  item: ChannelItem;
  estimate: ItemEstimate | null;
  invoice: ChannelInvoice | null;
}

/** Os cinco canais na ordem do cadastro, com a estimativa e a fatura do mês. */
export function invoiceRows(estimates: readonly ItemEstimate[], invoices: readonly ChannelInvoice[]): InvoiceRow[] {
  return CHANNEL_ITEMS.map((item) => ({
    item,
    estimate: estimates.find((e) => e.item === item) ?? null,
    invoice: invoices.find((i) => i.item === item) ?? null,
  }));
}
