'use strict';

let editingProductId = null;
let detailRequest = 0;
const byId = id => document.getElementById(id);
const money = (value, currency = 'EUR') => new Intl.NumberFormat('it-IT', {style: 'currency', currency}).format(value);
const orderDate = value => String(value).replace('T', ' ');

function notify(message, error = false) {
  const box = byId('admin-message');
  box.textContent = message;
  box.className = error ? 'error-message' : 'status-message';
  box.hidden = !message;
}

async function adminRequest(path, options = {}) {
  const headers = {Accept: 'application/json'};
  if (options.method) {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (!token || !header) throw new Error('Token di sicurezza assente. Ricarica la pagina.');
    headers[header] = token;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch('/api/admin' + path, {...options, credentials: 'same-origin', headers});
  if (response.status === 401 || response.redirected) throw new Error('Sessione scaduta. Accedi nuovamente per continuare.');
  if (response.status === 403) throw new Error('Accesso negato. Verifica la sessione amministratore e ricarica la pagina.');
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || 'Impossibile completare la richiesta. Riprova.');
  }
  return response.json();
}

function cell(row, value) {
  const td = document.createElement('td');
  td.textContent = value == null ? '—' : String(value);
  row.appendChild(td);
  return td;
}

function emptyRow(body, columns, message) {
  const row = document.createElement('tr');
  cell(row, message).colSpan = columns;
  body.appendChild(row);
}

function actionButton(label, action) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'button button-secondary';
  button.textContent = label;
  button.addEventListener('click', action);
  return button;
}

function renderProducts(products) {
  const body = byId('product-rows');
  body.replaceChildren();
  if (!products.length) emptyRow(body, 4, 'Nessun prodotto. Crea il primo prodotto dal modulo.');
  for (const product of products) {
    const row = document.createElement('tr');
    cell(row, product.nome);
    cell(row, product.categoria);
    cell(row, money(product.prezzo));
    const action = cell(row, '');
    action.appendChild(actionButton('Modifica #' + product.id, () => editProduct(product)));
    body.appendChild(row);
  }
}

function editProduct(product = null) {
  editingProductId = product?.id ?? null;
  const form = byId('product-form');
  form.reset();
  for (const field of ['nome', 'prezzo', 'categoria', 'descrizione', 'image']) {
    form.elements[field].value = product?.[field] ?? '';
  }
  byId('editor-title').textContent = product ? 'Modifica prodotto #' + product.id : 'Nuovo prodotto';
  byId('save-product').textContent = product ? 'Salva modifiche' : 'Crea prodotto';
  byId('cancel-edit').hidden = !product;
  byId('product-name').focus();
}

function renderOrders(orders) {
  const body = byId('order-rows');
  body.replaceChildren();
  if (!orders.length) emptyRow(body, 5, 'Nessun ordine confermato.');
  for (const order of orders) {
    const row = document.createElement('tr');
    cell(row, '#' + order.id);
    cell(row, orderDate(order.date));
    cell(row, money(order.total, order.currency));
    cell(row, order.shipment);
    cell(row, '').appendChild(actionButton('Dettaglio #' + order.id, () => showOrder(order.id)));
    body.appendChild(row);
  }
}

async function showOrder(id) {
  const request = ++detailRequest;
  try {
    const order = await adminRequest('/orders/' + id);
    if (request !== detailRequest) return;
    byId('detail-title').textContent = 'Ordine #' + order.id;
    byId('detail-summary').textContent = orderDate(order.date) + ' UTC · ' + order.shipment + ' · Totale ' + money(order.total, order.currency);
    const body = byId('detail-rows');
    body.replaceChildren();
    if (!order.items.length) emptyRow(body, 4, 'Nessun articolo presente.');
    for (const item of order.items) {
      const row = document.createElement('tr');
      cell(row, item.productName);
      cell(row, money(item.unitPrice, order.currency));
      cell(row, item.quantity);
      cell(row, money(item.subtotal, order.currency));
      body.appendChild(row);
    }
    byId('order-detail').hidden = false;
    byId('order-detail').focus();
  } catch (error) {
    if (request === detailRequest) notify(error.message, true);
  }
}

async function refreshAdmin() {
  byId('refresh').disabled = true;
  const results = await Promise.allSettled([
    adminRequest('/dashboard').then(data => {
      byId('total-orders').textContent = data.totalOrders;
      byId('total-products').textContent = data.totalProducts;
      byId('order-value').textContent = money(data.confirmedOrderValue, data.currency);
    }),
    adminRequest('/products').then(renderProducts).catch(error => {
      byId('product-rows').replaceChildren();
      emptyRow(byId('product-rows'), 4, 'Prodotti non disponibili. Usa Aggiorna dati per riprovare.');
      throw error;
    }),
    adminRequest('/orders').then(renderOrders).catch(error => {
      byId('order-rows').replaceChildren();
      emptyRow(byId('order-rows'), 5, 'Ordini non disponibili. Usa Aggiorna dati per riprovare.');
      throw error;
    })
  ]);
  byId('refresh').disabled = false;
  const failure = results.find(result => result.status === 'rejected');
  if (failure) { notify(failure.reason.message, true); return false; }
  return true;
}

async function saveProduct(event) {
  event.preventDefault();
  const form = byId('product-form');
  if (!form.reportValidity()) return;
  const payload = {};
  for (const field of ['nome', 'prezzo', 'categoria', 'descrizione', 'image']) payload[field] = form.elements[field].value.trim();
  if (!payload.nome) { notify('Inserisci il nome del prodotto.', true); return; }
  const id = editingProductId;
  byId('save-product').disabled = true;
  byId('new-product').disabled = true;
  byId('cancel-edit').disabled = true;
  notify('Salvataggio in corso…');
  try {
    await adminRequest('/products' + (id == null ? '' : '/' + id), {method: id == null ? 'POST' : 'PUT', body: JSON.stringify(payload)});
    editProduct();
    if (await refreshAdmin()) notify('Prodotto salvato.');
  } catch (error) {
    notify(error.message, true);
  } finally {
    byId('save-product').disabled = false;
    byId('new-product').disabled = false;
    byId('cancel-edit').disabled = false;
  }
}

document.addEventListener('DOMContentLoaded', () => {
  byId('product-form').addEventListener('submit', saveProduct);
  byId('new-product').addEventListener('click', () => editProduct());
  byId('cancel-edit').addEventListener('click', () => editProduct());
  byId('refresh').addEventListener('click', async () => {
    notify('Aggiornamento in corso…');
    if (await refreshAdmin()) notify('Dati aggiornati.');
  });
  byId('close-detail').addEventListener('click', () => {
    detailRequest++;
    byId('order-detail').hidden = true;
  });
  refreshAdmin();
});
