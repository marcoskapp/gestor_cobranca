import { ContactNovelty, ContactReceiptView } from './debt.models';

/** Texto do selo por situação do contato novo (o selo sempre leva ícone + texto, não só cor). */
export const CONTACT_NOVELTY_LABELS: Record<ContactNovelty, string> = {
  NEW: 'Novo',
  NEW_ANSWERED: 'Novo · atendeu',
  NEW_NO_ANSWER: 'Novo · sem resposta',
};

/** Selo de contato recebido na carga: texto visível e dica (também lida pelo leitor de tela). */
export interface ContactNoveltyBadgeText {
  /** Nulo: não há selo "Novo" (só a dica discreta de confirmação, se houver). */
  label: string | null;
  hint: string;
}

/** dd/MM/yyyy no fuso do navegador (o recebimento é um instante). */
export function formatReceiptDate(value: string | null): string {
  if (!value) {
    return '';
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split('-');
    return `${d}/${m}/${y}`;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const dd = String(date.getDate()).padStart(2, '0');
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  return `${dd}/${mm}/${date.getFullYear()}`;
}

/**
 * Selo e dica do contato a partir do recebimento por carga. Sem recebimento, ou sem novidade e sem reenvio,
 * devolve nulo (nada a exibir).
 */
export function contactNoveltyBadge(receipt: ContactReceiptView | null | undefined): ContactNoveltyBadgeText | null {
  if (!receipt) {
    return null;
  }
  const parts: string[] = [];
  const received = formatReceiptDate(receipt.receivedAt);
  if (receipt.novelty) {
    const since = received ? `Recebido na carga de ${received}` : 'Recebido numa nova carga';
    if (receipt.novelty === 'NEW') {
      parts.push(`${since}; ainda sem tentativa`);
    } else {
      const tested = formatReceiptDate(receipt.firstAttemptAt);
      const result = receipt.novelty === 'NEW_ANSWERED' ? 'atendeu' : 'sem resposta';
      parts.push(`${since}; já testado${tested ? ` em ${tested}` : ''} (${result})`);
    }
  }
  const confirmed = formatReceiptDate(receipt.confirmedAt);
  if (confirmed) {
    parts.push(`Confirmado pelo credor em ${confirmed}`);
  }
  if (parts.length === 0) {
    return null;
  }
  return { label: receipt.novelty ? CONTACT_NOVELTY_LABELS[receipt.novelty] : null, hint: parts.join('. ') + '.' };
}
