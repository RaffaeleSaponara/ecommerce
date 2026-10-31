const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/resources');
const source = fs.readFileSync(path.join(root, 'static/js/prodotto.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'templates/prodotto.html'), 'utf8');
const imageUrl = '/media/products/12345678-1234-1234-1234-123456789abc.png';
function page(response = {ok: true, json: async () => ({items: []})}, soldOut = false) {
  function element(dataset = {}) {return {dataset, listeners: {}, textContent: '', disabled: false,
    addEventListener(name, callback) {this.listeners[name] = callback;},
    setAttribute(name, value) {this[name] = value;}};}
  const ids = {'product-main-image': {...element(), src: '/images/b1.jpg'}, 'detail-add-cart': element({productId: '7'}), 'detail-cart-status': element()};
  const thumbs = [element({imageUrl}), element({imageUrl: 'javascript:alert(1)'})];
  ids['detail-add-cart'].disabled = soldOut;
  ids['detail-stock'] = element();
  const calls = []; let ready;
  const context = vm.createContext({document: {getElementById: id => ids[id], querySelectorAll: () => thumbs,
    querySelector: selector => ({content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-test'}),
    addEventListener: (_, callback) => ready = callback},
    fetch: async (url, options) => {calls.push({url, options}); return response;}});
  vm.runInContext(source, context); ready();
  return {ids, thumbs, calls};
}
test('detail renders product text and exact BigDecimal server-side, with protected local image fallback', () => {
  assert.match(html, /th:text="\$\{detail.product.nome\}"/);
  assert.match(html, /th:text="\$\{detail.product.descrizione\}"/);
  assert.match(html, /prezzo.toPlainString\(\)/);
  assert.match(html, /detail.images/);
  assert.match(html, /product-placeholder/);
  assert.doesNotMatch(html, /th:utext|innerHTML/);
});
test('gallery switches only to managed images and updates accessible selection', () => {
  const p = page();
  p.thumbs[0].listeners.click();
  assert.equal(p.ids['product-main-image'].src, imageUrl);
  assert.equal(p.thumbs[0]['aria-pressed'], 'true');
  assert.equal(p.thumbs[1]['aria-pressed'], 'false');
  p.thumbs[1].listeners.click();
  assert.equal(p.ids['product-main-image'].src, imageUrl);
  p.ids['product-main-image'].listeners.error();
  assert.equal(p.ids['product-main-image'].src, '/images/product-placeholder.svg');
});
test('detail cart uses same-origin session, CSRF and product id, with visible success', async () => {
  const p = page(); await p.ids['detail-add-cart'].listeners.click();
  assert.equal(p.calls[0].url, '/cart/add');
  assert.equal(p.calls[0].options.method, 'POST');
  assert.equal(p.calls[0].options.credentials, 'same-origin');
  assert.equal(p.calls[0].options.headers['X-CSRF-TOKEN'], 'csrf-test');
  assert.equal(p.calls[0].options.body, '7');
  assert.match(p.ids['detail-cart-status'].textContent, /aggiunto/);
  assert.equal(p.ids['detail-add-cart'].disabled, false);
});
test('detail cart reports expired session, forbidden and server errors and allows retry', async () => {
  for (const response of [{ok: true, redirected: true}, {ok: false, status: 401}, {ok: false, status: 403}, {ok: false, status: 500}]) {
    const p = page(response); await p.ids['detail-add-cart'].listeners.click();
    assert.doesNotMatch(p.ids['detail-cart-status'].textContent, /aggiunto/);
    assert.equal(p.ids['detail-add-cart'].disabled, false);
  }
});

test('detail blocks sold-out products and stops at the quantity returned by the cart',async()=>{
  const empty=page(undefined,true);await empty.ids['detail-add-cart'].listeners.click();assert.equal(empty.calls.length,0);
  const p=page({ok:true,json:async()=>({items:[{id:7,stock:1,quantita:1}]})});
  await p.ids['detail-add-cart'].listeners.click();assert.equal(p.ids['detail-add-cart'].disabled,true);
  assert.match(p.ids['detail-stock'].textContent,/Quantità massima/);
});
