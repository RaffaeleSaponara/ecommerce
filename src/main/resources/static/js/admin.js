'use strict';

let editingProductId = null;
let originalStock = '0';
let detailRequest = 0;
let orderUpdateBusy = false;
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
    if (!(options.body instanceof FormData)) headers['Content-Type'] = 'application/json';
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
  if (!products.length) emptyRow(body, 5, 'Nessun prodotto. Crea il primo prodotto dal modulo.');
  for (const product of products) {
    const row = document.createElement('tr');
    cell(row, product.nome + (product.active === false ? ' — ARCHIVIATO' : ' — ATTIVO'));
    cell(row, product.categoria);
    cell(row, money(product.prezzo));
    cell(row, product.stock === 0 ? '0 — Esaurito' : product.stock);
    const action = cell(row, '');
    action.appendChild(actionButton('Modifica #' + product.id, () => editProduct(product)));
    action.appendChild(actionButton(product.active === false ? 'Riattiva' : 'Archivia', async () => {
      try {
        await adminRequest('/products/' + product.id + '/publication', {
          method: 'PUT', body: JSON.stringify({active: product.active === false})
        });
        renderProducts(await adminRequest('/products'));
        notify(product.active === false ? 'Prodotto riattivato.' : 'Prodotto archiviato.');
      } catch (error) { notify(error.message, true); }
    }));
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
  originalStock = String(product?.stock ?? 0);
  form.elements.stock.value = originalStock;
  byId('editor-title').textContent = product ? 'Modifica prodotto #' + product.id : 'Nuovo prodotto';
  byId('save-product').textContent = product ? 'Salva modifiche' : 'Crea prodotto';
  byId('cancel-edit').hidden = !product;
  resetProductImages();
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

const orderStatusLabels = {CONFERMATO: 'Confermato', IN_PREPARAZIONE: 'In preparazione', SPEDITO: 'Spedito', CONSEGNATO: 'Consegnato', ANNULLATO: 'Annullato'};

function orderTransitions(shipment) {
  const status = shipment === 'In elaborazione' ? 'CONFERMATO' : shipment;
  const next = {CONFERMATO: 'IN_PREPARAZIONE', IN_PREPARAZIONE: 'SPEDITO', SPEDITO: 'CONSEGNATO'}[status];
  return [...(next ? [next] : []), ...(['CONFERMATO', 'IN_PREPARAZIONE'].includes(status) ? ['ANNULLATO'] : [])];
}

function renderOrderActions(order) {
  const actions = byId('order-status-actions');
  actions.replaceChildren();
  const transitions = orderTransitions(order.shipment);
  for (const status of transitions) {
    const button = actionButton(status === 'ANNULLATO' ? 'Annulla ordine' : 'Segna come ' + orderStatusLabels[status].toLowerCase(), () => updateOrderStatus(order.id, status));
    button.disabled = orderUpdateBusy;
    actions.appendChild(button);
  }
  if (!transitions.length) actions.textContent = 'Nessuna transizione disponibile per questo stato.';
}

function renderOrderHistory(order) {
  const history = byId('order-status-history');
  history.replaceChildren();
  for (const change of order.statusHistory || []) {
    const item = document.createElement('li');
    item.textContent = orderDate(change.date) + ' UTC · ' + (orderStatusLabels[change.previousStatus] || change.previousStatus) + ' → ' + (orderStatusLabels[change.newStatus] || change.newStatus);
    history.appendChild(item);
  }
  if (!history.children.length) history.textContent = 'Nessun cambio di stato registrato.';
}

async function updateOrderStatus(id, status) {
  if (orderUpdateBusy) return;
  if (status === 'ANNULLATO' && !window.confirm('Annullare l’ordine #' + id + '? Le quantità saranno reintegrate nel magazzino.')) return;
  orderUpdateBusy = true;
  byId('order-status-actions').querySelectorAll('button').forEach(button => { button.disabled = true; });
  notify('Aggiornamento stato ordine…');
  try {
    await adminRequest('/orders/' + id + '/status', {method: 'POST', body: JSON.stringify({status})});
    const refreshed = await refreshAdmin();
    // Reload persisted state rather than assuming that another admin has not changed it.
    if (!byId('order-detail').hidden && byId('order-detail').dataset.orderId === String(id)) await showOrder(id);
    if (refreshed) notify(status === 'ANNULLATO' ? 'Ordine annullato. Scorte reintegrate.' : 'Stato ordine aggiornato.');
  } catch (error) {
    notify(error.message, true);
  } finally {
    orderUpdateBusy = false;
    byId('order-status-actions').querySelectorAll('button').forEach(button => { button.disabled = false; });
  }
}

async function showOrder(id) {
  const request = ++detailRequest;
  try {
    const order = await adminRequest('/orders/' + id);
    if (request !== detailRequest) return;
    byId('detail-title').textContent = 'Ordine #' + order.id;
    byId('detail-summary').textContent = orderDate(order.date) + ' UTC · ' + order.shipment + ' · Totale ' + money(order.total, order.currency);
    byId('detail-amounts').textContent = 'Subtotale prodotti: ' + money(order.subtotal, order.currency) + ' · Spedizione: ' + (Number(order.shippingCost) === 0 ? 'Gratuita' : money(order.shippingCost, order.currency));
    byId('tracking-carrier').value = order.carrier || '';
    byId('tracking-code').value = order.trackingCode || '';
    byId('order-detail').dataset.orderId = String(order.id);
    renderOrderActions(order);
    renderOrderHistory(order);
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
    loadShippingSettings(),
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

async function loadShippingSettings() {
  const settings = await adminRequest('/shipping-settings');
  byId('shipping-cost').value = settings.cost;
  byId('shipping-threshold').value = settings.freeThreshold;
}

function validShippingAmount(value) {
  return /^\d+(?:\.\d{1,2})?$/.test(value) && Number.isFinite(Number(value)) && Number(value) <= 99999999999999999.99;
}

async function saveShippingSettings(event) {
  event.preventDefault();
  const cost = byId('shipping-cost').value.trim(), freeThreshold = byId('shipping-threshold').value.trim();
  if (!validShippingAmount(cost) || !validShippingAmount(freeThreshold)) {
    notify('Inserisci importi non negativi con al massimo due decimali.', true); return;
  }
  byId('save-shipping').disabled = true;
  try {
    const settings = await adminRequest('/shipping-settings', {method: 'PUT', body: JSON.stringify({cost, freeThreshold})});
    byId('shipping-cost').value = settings.cost;
    byId('shipping-threshold').value = settings.freeThreshold;
    notify('Tariffe salvate. I preventivi con importi diversi devono essere riconfermati.');
  } catch (error) { notify(error.message, true); }
  finally { byId('save-shipping').disabled = false; }
}

async function saveOrderTracking(event) {
  event.preventDefault();
  const id = byId('order-detail').dataset.orderId;
  if (!id) return;
  const carrier = byId('tracking-carrier').value.trim(), trackingCode = byId('tracking-code').value.trim();
  if (carrier.length > 100 || trackingCode.length > 150) { notify('Corriere o codice di tracciamento troppo lungo.', true); return; }
  byId('save-tracking').disabled = true;
  try {
    await adminRequest('/orders/' + id + '/tracking', {method: 'PUT', body: JSON.stringify({carrier, trackingCode})});
    if (byId('order-detail').dataset.orderId === id) await showOrder(id);
    notify('Tracking salvato.');
  } catch (error) { notify(error.message, true); }
  finally { byId('save-tracking').disabled = false; }
}

async function saveProduct(event) {
  event.preventDefault();
  const form = byId('product-form');
  if (!form.reportValidity()) return;
  const payload = {};
  for (const field of ['nome', 'prezzo', 'categoria', 'descrizione', 'image']) payload[field] = form.elements[field].value.trim();
  if (!payload.nome) { notify('Inserisci il nome del prodotto.', true); return; }
  const id = editingProductId;
  const stock = form.elements.stock.value.trim();
  if (!/^[0-9]+$/.test(stock) || Number(stock) > 2147483647) { notify('Inserisci una quantità intera tra 0 e 2147483647.', true); return; }
  if (id == null || Number(stock) !== Number(originalStock)) payload.stock = stock;
  byId('save-product').disabled = true;
  byId('new-product').disabled = true;
  byId('cancel-edit').disabled = true;
  notify('Salvataggio in corso…');
  try {
    const saved = await adminRequest('/products' + (id == null ? '' : '/' + id), {method: id == null ? 'POST' : 'PUT', body: JSON.stringify(payload)});
    editProduct(saved && saved.id != null ? saved : null);
    if (await refreshAdmin()) notify('Prodotto salvato.');
  } catch (error) {
    notify(error.message, true);
  } finally {
    byId('save-product').disabled = imageBusy;
    byId('new-product').disabled = false;
    byId('cancel-edit').disabled = false;
  }
}

document.addEventListener('DOMContentLoaded', () => {
  byId('shipping-settings-form').addEventListener('submit', saveShippingSettings);
  byId('order-tracking-form').addEventListener('submit', saveOrderTracking);
  byId('product-image-upload-form').addEventListener('submit', uploadProductImage);
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

let imageVersion = 0;
let imageController = null;
let imageCount = 0;
let imageBusy = false;

function imageMessage(text, error = false) {
  const message = byId('product-images-message');
  message.textContent = text;
  message.className = error ? 'error-message' : 'status-message';
}

function imageControls(busy) {
  imageBusy = busy;
  byId('save-product').disabled = busy;
  byId('upload-product-image').disabled = busy || imageCount >= 10;
  byId('product-image-file').disabled = busy || imageCount >= 10;
  byId('product-images-list').querySelectorAll('button').forEach(button => { button.disabled = busy || button.dataset.primary === 'true'; });
}

function resetProductImages() {
  ++imageVersion;
  if (imageController) imageController.abort();
  imageController = new AbortController();
  imageCount = 0;
  byId('product-images').hidden = editingProductId == null;
  byId('product-image-file').value = '';
  byId('product-images-list').replaceChildren();
  imageControls(false);
  imageMessage('');
  if (editingProductId != null) loadProductImages(editingProductId, imageVersion);
}

function managedImageUrl(url) {
  return /^\/media\/products\/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\.png$/.test(String(url));
}

async function loadProductImages(id, version) {
  imageControls(true);
  imageMessage('Caricamento immagini…');
  try {
    const images = await adminRequest('/products/' + id + '/images', {signal: imageController.signal});
    if (version !== imageVersion) return;
    renderProductImages(images);
    imageMessage(images.length ? images.length + ' di 10 immagini.' : 'Nessuna immagine caricata. Puoi aggiungere la prima.');
  } catch (error) {
    if (version === imageVersion) imageMessage(error.message, true);
  } finally {
    if (version === imageVersion) imageControls(false);
  }
}

function renderProductImages(images) {
  const list = byId('product-images-list');
  list.replaceChildren();
  imageCount = images.length;
  images.forEach((image, index) => {
    const item = document.createElement('li');
    const preview = document.createElement('img');
    preview.src = managedImageUrl(image.url) ? image.url : '/images/product-placeholder.svg';
    preview.addEventListener('error', () => {
      if (!preview.src.endsWith('/images/product-placeholder.svg')) preview.src = '/images/product-placeholder.svg';
    });
    preview.alt = 'Immagine ' + (index + 1);
    item.appendChild(preview);
    const primary = actionButton(image.primary ? 'Principale' : 'Imposta principale', () => mutateProductImage('/' + image.id + '/primary', {method: 'PUT'}));
    primary.dataset.primary = String(image.primary);
    primary.disabled = image.primary;
    item.appendChild(primary);
    item.appendChild(actionButton('Rimuovi immagine ' + (index + 1), () => mutateProductImage('/' + image.id, {method: 'DELETE'})));
    list.appendChild(item);
  });
}

async function mutateProductImage(suffix, options) {
  if (editingProductId == null || imageBusy) return;
  const id = editingProductId, version = imageVersion;
  imageControls(true);
  imageMessage('Aggiornamento immagini…');
  try {
    await adminRequest('/products/' + id + '/images' + suffix, {...options, signal: imageController.signal});
    if (version !== imageVersion) return;
    byId('product-image-file').value = '';
    // Refresh the persisted image field too, so a subsequent product save cannot restore an old primary.
    const products = await adminRequest('/products', {signal: imageController.signal});
    if (version !== imageVersion) return;
    renderProducts(products);
    const product = products.find(product => product.id === id);
    if (product) byId('product-form').elements.image.value = product.image || '';
    await loadProductImages(id, version);
  } catch (error) {
    if (version === imageVersion) imageMessage(error.message, true);
  } finally {
    if (version === imageVersion) imageControls(false);
  }
}

function uploadProductImage(event) {
  event.preventDefault();
  if (editingProductId == null || imageBusy) return;
  const file = byId('product-image-file').files[0];
  if (!file) { imageMessage('Scegli un file JPEG o PNG.', true); return; }
  if (!['image/jpeg', 'image/png'].includes(file.type)) { imageMessage('Sono consentite solo immagini JPEG e PNG.', true); return; }
  if (!file.size || file.size > 5 * 1024 * 1024) { imageMessage('Il file deve avere una dimensione massima di 5 MiB.', true); return; }
  if (imageCount >= 10) { imageMessage('Puoi caricare al massimo 10 immagini per prodotto.', true); return; }
  const body = new FormData();
  body.append('file', file);
  return mutateProductImage('', {method: 'POST', body});
}
