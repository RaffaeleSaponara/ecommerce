'use strict';

let editingProductId = null;
let originalStock = '0';
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
    cell(row, product.nome);
    cell(row, product.categoria);
    cell(row, money(product.prezzo));
    cell(row, product.stock === 0 ? '0 — Esaurito' : product.stock);
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
