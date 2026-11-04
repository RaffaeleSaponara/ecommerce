'use strict';
document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('technical-form');
  if (!form) return;
  const status = document.getElementById('technical-status');
  const load = document.getElementById('technical-load');
  const saveButton = form.querySelector('button[type="submit"]');
  const fields = ['modelCode', 'identification', 'materials', 'dimensions', 'manufacturerName', 'manufacturerAddress', 'manufacturerEmail', 'warnings', 'instructions'];
  let busy = false, loadedId = null;
  function show(result) {
    for (const key of fields) form.elements[key].value = result.data[key] ?? '';
    form.elements.weightGrams.value = result.data.weightGrams ?? '';
    form.elements.verified.checked = result.data.verified;
    status.textContent = result.missing.length ? 'Non pubblicata / da completare: ' + result.missing.join(', ') :
      result.data.verified ? 'Scheda completa e visibile ai clienti.' : 'Scheda completa ma non confermata: non visibile ai clienti.';
  }
  async function run(save) {
    if (busy) return;
    const id = form.elements.productId.value;
    if (!/^[1-9][0-9]*$/.test(id)) { status.textContent = 'Inserisci un ID prodotto valido.'; return; }
    if (save && loadedId !== id) { status.textContent = 'Carica prima la scheda del prodotto selezionato.'; return; }
    busy = true;
    load.disabled = saveButton.disabled = form.elements.productId.disabled = true;
    try {
      let options = {};
      if (save) {
        if (!form.reportValidity()) return;
        const data = {};
        for (const key of fields) data[key] = form.elements[key].value.trim();
        data.weightGrams = form.elements.weightGrams.value || null;
        data.verified = form.elements.verified.checked;
        options = { method: 'PUT', body: JSON.stringify(data) };
      }
      const result = await adminRequest('/products/' + id + '/information', options);
      show(result);
      loadedId = id;
    } catch (error) { status.textContent = error.message; }
    finally { busy = false; load.disabled = saveButton.disabled = form.elements.productId.disabled = false; }
  }
  load.addEventListener('click', () => run(false));
  form.addEventListener('submit', event => { event.preventDefault(); run(true); });
});
