const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/admin.js'), 'utf8');

function page(responder = () => ({ok: true, json: async () => []})) {
  const elements = new Map(), calls = [], listeners = {};
  function element(tag) {
    return {tag, children: [], listeners: {}, hidden: false, disabled: false, value: '', textContent: '',
      appendChild(child) {this.children.push(child);},
      replaceChildren() {this.children = [];},
      addEventListener(name, action) {this.listeners[name] = action;},
      focus() {this.focused = true;},
      set innerHTML(value) {throw new Error('Unsafe HTML: ' + value);}
    };
  }
  function byId(id) {
    if (!elements.has(id)) elements.set(id, element('div'));
    return elements.get(id);
  }
  const form = byId('product-form');
  form.elements = Object.fromEntries(['nome', 'prezzo', 'categoria', 'descrizione', 'image'].map(name => [name, element('input')]));
  form.reportValidity = () => true;
  form.reset = () => Object.values(form.elements).forEach(field => field.value = '');
  const context = vm.createContext({Intl, console,
    document: {getElementById: byId, createElement: element,
      querySelector: selector => ({content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-test'}),
      addEventListener: (name, action) => listeners[name] = action},
    fetch: async (url, options) => {calls.push({url, options}); return responder(url, options);}
  });
  vm.runInContext(source, context);
  return {context, byId, calls, listeners, run: expression => vm.runInContext(expression, context)};
}
const product = {id: 7, nome: 'Jig', prezzo: '12.50', categoria: 'Pesca', descrizione: 'Artigianale', image: '/images/b1.jpg'};
const order = {id: 3, date: '2026-10-28T12:00:00', total: '25.00', currency: 'EUR', shipment: 'In elaborazione', items: [{productName: 'Jig', unitPrice: '12.50', quantity: 2, subtotal: '25.00'}]};

test('product and order content remain text, edit retains product fields', () => {
  const p = page();
  const malicious = '<img src=x onerror=alert(1)>';
  p.context.products = [{...product, nome: malicious, categoria: malicious}];
  p.run('renderProducts(products)');
  const row = p.byId('product-rows').children[0];
  assert.equal(row.children[0].textContent, malicious);
  assert.equal(row.children[1].textContent, malicious);
  row.children[3].children[0].listeners.click();
  assert.equal(p.byId('product-form').elements.nome.value, malicious);
  assert.equal(p.byId('product-form').elements.prezzo.value, '12.50');
  assert.equal(p.byId('editor-title').textContent, 'Modifica prodotto #7');
  p.context.orders = [{...order, shipment: malicious}];
  p.run('renderOrders(orders)');
  assert.equal(p.byId('order-rows').children[0].children[3].textContent, malicious);
});

test('create and edit use correct endpoint, CSRF and same-origin session then refresh persisted totals', async () => {
  const p = page(url => ({ok: true, json: async () => url.endsWith('/dashboard') ? {totalOrders: 3, totalProducts: 9, confirmedOrderValue: '25.00', currency: 'EUR'} : []}));
  const form = p.byId('product-form');
  Object.assign(form.elements.nome, {value: ' Jig '});
  form.elements.prezzo.value = '12.50';
  await p.run('saveProduct({preventDefault(){}})');
  const create = p.calls[0];
  assert.equal(create.url, '/api/admin/products');
  assert.equal(create.options.method, 'POST');
  assert.equal(create.options.credentials, 'same-origin');
  assert.equal(create.options.headers['X-CSRF-TOKEN'], 'csrf-test');
  assert.equal(JSON.parse(create.options.body).nome, 'Jig');
  assert.equal(JSON.parse(create.options.body).prezzo, '12.50');
  assert.equal(p.byId('total-orders').textContent, 3);
  assert.equal(p.byId('total-products').textContent, 9);
  assert.match(p.byId('order-value').textContent, /25,00/);
  p.context.product = product;
  p.run('editProduct(product)');
  await p.run('saveProduct({preventDefault(){}})');
  const update = p.calls.find(call => call.options.method === 'PUT');
  assert.equal(update.url, '/api/admin/products/7');
  assert.equal(p.byId('save-product').disabled, false);
});

test('HTTP validation errors preserve input and re-enable saving', async () => {
  const p = page(() => ({ok: false, status: 400, json: async () => ({message: 'Prezzo non valido'})}));
  p.context.product = product;
  p.run('editProduct(product)');
  await p.run('saveProduct({preventDefault(){}})');
  assert.equal(p.byId('admin-message').textContent, 'Prezzo non valido');
  assert.equal(p.byId('admin-message').className, 'error-message');
  assert.equal(p.byId('product-form').elements.nome.value, 'Jig');
  assert.equal(p.byId('save-product').disabled, false);
  assert.equal(p.calls.length, 1);
});

test('expired session and forbidden API responses never render data', async () => {
  for (const response of [{status: 401}, {status: 403}, {status: 200, redirected: true}]) {
    const p = page(() => ({ok: false, ...response, json: async () => {throw new Error('must not parse');}}));
    assert.equal(await p.run('refreshAdmin()'), false);
    assert.equal(p.byId('admin-message').className, 'error-message');
    assert.equal(p.byId('refresh').disabled, false);
    assert.match(p.byId('product-rows').children[0].children[0].textContent, /non disponibili/);
  }
});

test('detail loads persisted snapshot fields and handles missing orders visibly', async () => {
  const p = page(() => ({ok: true, json: async () => order}));
  await p.run('showOrder(3)');
  assert.equal(p.calls[0].url, '/api/admin/orders/3');
  assert.equal(p.byId('order-detail').hidden, false);
  assert.equal(p.byId('detail-rows').children[0].children[0].textContent, 'Jig');
  assert.equal(p.byId('detail-rows').children[0].children[2].textContent, '2');
  assert.match(p.byId('detail-summary').textContent, /In elaborazione/);
  const missing = page(() => ({ok: false, status: 404, json: async () => ({message: 'Ordine non trovato'})}));
  await missing.run('showOrder(999)');
  assert.equal(missing.byId('admin-message').textContent, 'Ordine non trovato');
});

test('empty collections show actionable empty states', () => {
  const p = page();
  p.run('renderProducts([]); renderOrders([])');
  assert.match(p.byId('product-rows').children[0].children[0].textContent, /Nessun prodotto/);
  assert.match(p.byId('order-rows').children[0].children[0].textContent, /Nessun ordine/);
});
