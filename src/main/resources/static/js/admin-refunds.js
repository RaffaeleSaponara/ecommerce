(() => {
  let selected = null, sequence = 0, busy = false;
  const byId = id => document.getElementById(id);
  const labels = {REQUESTED: 'Richiesto', PENDING: 'In verifica / in corso', SUCCEEDED: 'Rimborso confermato', FAILED: 'Rimborso non riuscito'};
  const issues = {REFUND_FAILED: 'Rimborso fallito: l’ordine resta annullato.', REVIEW_REQUIRED: 'Necessaria verifica amministrativa; nessun nuovo invio automatico.', ACTION_REQUIRED: 'Il provider richiede un intervento.', EXISTING_REFUND_CONFLICT: 'Rimborsi esistenti non riconciliabili automaticamente.'};
  const description = data => `${labels[data.refundStatus || data.status] || 'Nessun rimborso'}${data.providerStatus ? ' · Stripe: ' + data.providerStatus : ''}${data.issue ? ' · ' + (issues[data.issue] || 'Verifica necessaria: ' + data.issue) : ''}`;
  async function refreshLists() {
    if (!byId('stripe-financial-panel')) return;
    try {
      const [refunds, anomalies] = await Promise.all([adminRequest('/stripe/refunds'), adminRequest('/stripe/anomalies')]);
      byId('stripe-refund-list').replaceChildren();
      for (const refund of refunds) {
        const li = document.createElement('li');
        li.textContent = `Ordine #${refund.orderId}: ${description(refund)} · ${money(refund.amount, 'EUR')}`;
        byId('stripe-refund-list').appendChild(li);
      }
      byId('stripe-anomaly-list').replaceChildren();
      for (const anomaly of anomalies) {
        const li = document.createElement('li');
        li.textContent = `Anomalia irrisolta ${anomaly.eventId} · ordine ${anomaly.orderId ?? 'non associato'}`;
        byId('stripe-anomaly-list').appendChild(li);
      }
    } catch (_) { byId('stripe-financial-status').textContent = 'Stato finanziario non disponibile. Riprova con Aggiorna.'; }
  }
  window.showOrderFinancials = async id => {
    if (!byId('stripe-financial-panel')) return;
    selected = id; const current = ++sequence;
    byId('stripe-financial-actions').replaceChildren();
    try {
      const state = await adminRequest('/stripe/orders/' + id);
      if (current !== sequence) return;
      byId('stripe-financial-status').textContent = `Ordine #${id} · pagamento: ${state.paymentStatus} · ${description(state)} · anomalie irrisolte: ${state.anomalies}`;
      if (state.paymentStatus !== 'LEGACY') {
        for (const button of document.getElementById('order-status-actions').querySelectorAll('button')) {
          if (button.textContent === 'Annulla ordine') button.remove();
        }
      }
      for (const [allowed, action, text] of [[state.canCancel, 'cancel-refund', 'Annulla e richiedi rimborso integrale'], [state.canRefundLate, 'refund', 'Verifica incasso e richiedi rimborso integrale']]) {
        if (!allowed) continue;
        const button = document.createElement('button'); button.type = 'button'; button.textContent = text; button.disabled = busy;
        button.addEventListener('click', async () => {
          if (busy || selected !== id || !window.confirm(`${text} per l’ordine #${id}, spedizione inclusa? Totale ${money(state.amount, 'EUR')}. La richiesta non significa che il rimborso sia già completato.`)) return;
          busy = true; button.disabled = true; let failure = null;
          try {
            await adminRequest(`/stripe/orders/${id}/${action}`, {method: 'POST', body: JSON.stringify({confirmed: true})});
            if (selected === id) await showOrder(id);
          } catch (error) { failure = error.message; }
          finally { busy = false; button.disabled = false; await refreshLists(); if (selected === id) await window.showOrderFinancials(id); if (failure && selected === id) byId('stripe-financial-status').textContent = failure; }
        });
        byId('stripe-financial-actions').appendChild(button);
      }
    } catch (_) { if (current === sequence) byId('stripe-financial-status').textContent = 'Impossibile verificare il pagamento di questo ordine.'; }
  };
  document.addEventListener('DOMContentLoaded', () => {
    if (!byId('stripe-financial-panel')) return;
    byId('stripe-financial-refresh').addEventListener('click', async () => { await refreshLists(); if (selected !== null) await window.showOrderFinancials(selected); });
    refreshLists();
  });
})();
