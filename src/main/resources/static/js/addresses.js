/* Shared address book: all customer-controlled values are rendered as text. */
const AddressBook = {
  mount({ canMutate = () => true, onChange = () => {}, onBusy = () => {} } = {}) {
    const root = document.getElementById('address-book');
    let addresses = [], editingId = null, busy = false;
    function el(tag, text) {
      const node = document.createElement(tag);
      if (text !== undefined) node.textContent = text;
      return node;
    }
    const status = el('p'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    const list = el('div'); list.className = 'address-list';
    const form = el('form'); form.className = 'address-form';
    const heading = el('h3', 'Nuovo indirizzo'); form.appendChild(heading);
    const fields = {};
    const definitions = [
      ['recipientName', 'Destinatario', 150], ['street', 'Via / piazza', 180],
      ['houseNumber', 'Numero civico', 20], ['postalCode', 'CAP', 5],
      ['municipality', 'Comune', 100], ['province', 'Provincia (sigla)', 2],
      ['deliveryNotes', 'Note di consegna (facoltative)', 500]
    ];
    for (const [name, title, maxLength] of definitions) {
      const label = el('label', title);
      const input = el(name === 'deliveryNotes' ? 'textarea' : 'input');
      input.name = name; input.id = 'address-' + name; input.maxLength = maxLength;
      input.required = name !== 'deliveryNotes';
      if (name === 'postalCode') { input.pattern = '[0-9]{5}'; input.inputMode = 'numeric'; }
      if (name === 'province') { input.pattern = '[A-Za-z]{2}'; }
      label.setAttribute('for', input.id); label.appendChild(input); form.appendChild(label); fields[name] = input;
    }
    form.appendChild(el('p', 'Paese: Italia (IT)'));
    const defaultLabel = el('label', 'Usa come predefinito');
    const defaultInput = el('input'); defaultInput.type = 'checkbox'; defaultLabel.prepend(defaultInput);
    form.appendChild(defaultLabel);
    const save = el('button', 'Salva indirizzo'); save.type = 'submit'; form.appendChild(save);
    const cancel = el('button', 'Annulla modifica'); cancel.type = 'button'; cancel.hidden = true;
    form.appendChild(cancel);
    root.replaceChildren(status, list, form);
    function reset() {
      editingId = null; form.reset(); heading.textContent = 'Nuovo indirizzo'; cancel.hidden = true;
    }
    cancel.addEventListener('click', () => { if (!busy && canMutate()) reset(); });
    function button(text, action) {
      const node = el('button', text); node.type = 'button'; node.disabled = busy;
      node.addEventListener('click', action); return node;
    }
    function render() {
      list.replaceChildren();
      if (!addresses.length) list.appendChild(el('p', 'Non hai ancora salvato indirizzi.'));
      for (const address of addresses) {
        const card = el('article'); card.className = 'address-card';
        card.appendChild(el('strong', address.recipientName + (address.defaultAddress ? ' (predefinito)' : '')));
        card.appendChild(el('p', `${address.street} ${address.houseNumber}, ${address.postalCode} ${address.municipality} (${address.province}) ${address.country}`));
        if (address.deliveryNotes) card.appendChild(el('p', address.deliveryNotes));
        card.appendChild(button('Modifica', () => {
          if (busy || !canMutate()) return;
          editingId = address.id;
          for (const [name, field] of Object.entries(fields)) field.value = address[name] || '';
          defaultInput.checked = address.defaultAddress; heading.textContent = 'Modifica indirizzo'; cancel.hidden = false;
          fields.recipientName.focus();
        }));
        if (!address.defaultAddress) card.appendChild(button('Imposta predefinito', () => mutate('/' + address.id + '/default', 'PUT')));
        card.appendChild(button('Elimina', () => {
          if (busy || !canMutate()) return;
          if (window.confirm('Eliminare questo indirizzo dalla rubrica? Gli ordini già effettuati conservano i loro indirizzi.')) mutate('/' + address.id, 'DELETE');
        }));
        list.appendChild(card);
      }
    }
    async function request(suffix = '', options = {}) {
      const response = await fetch('/api/addresses' + suffix, options);
      if (!response.ok) {
        let message = 'Rubrica non disponibile. Verifica la sessione e riprova.';
        try { const data = await response.json(); if (data.message) message = data.message; } catch (_) { /* use fallback */ }
        throw new Error(message);
      }
      return response.json();
    }
    async function load() {
      addresses = await request(); render(); onChange(addresses);
      status.textContent = addresses.length ? '' : 'Aggiungi un indirizzo per completare il checkout.';
    }
    async function mutate(suffix, method, data) {
      if (busy || !canMutate()) { status.textContent = 'Attendi il completamento della richiesta in corso.'; return; }
      busy = true; onBusy(true); save.disabled = true;
      try {
        const headers = { [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content };
        if (data) headers['Content-Type'] = 'application/json';
        await request(suffix, { method, headers, ...(data ? { body: JSON.stringify(data) } : {}) });
        reset(); await load(); status.textContent = 'Rubrica aggiornata.';
      } catch (error) { status.textContent = error.message; }
      finally { busy = false; onBusy(false); save.disabled = false; render(); }
    }
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      const data = { country: 'IT', defaultAddress: defaultInput.checked };
      for (const [name, field] of Object.entries(fields)) data[name] = field.value.trim();
      data.province = data.province.toUpperCase();
      return mutate(editingId === null ? '' : '/' + editingId, editingId === null ? 'POST' : 'PUT', data);
    });
    status.textContent = 'Caricamento rubrica…';
    return load().catch(error => { status.textContent = error.message; });
  }
};
