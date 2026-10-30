const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/cart.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const quote = { quoteToken: 'signed.exact-token', expiresAt: 1803643500, currency: 'EUR', total: 0.4,
  items: [{ productName: '<script>untrusted name</script>', quantity: 2, unitPrice: 0.2, subtotal: 0.4 }] };

function page({ storage = new Map(),
  quoteResponse = async () => ({ ok: true, text: async () => JSON.stringify(quote) }),
  cartMutationResponse = async () => ({ ok: true }),
  cartViewResponse = async () => ({ ok: true, text: async () => '{"items":[]}' }),
  checkoutResponse = async () => ({ ok: true, text: async () => '{"id":42,"total":0.40}' }) } = {}) {
  const elements = new Map();
  const listeners = new Map();
  function element() {
    return { style: {}, disabled: false, checked: false, hidden: false, textContent: '', children: [],
      attributes: {}, listeners: {},
      setAttribute(name, value) { this.attributes[name] = value; },
      addEventListener(name, callback) { this.listeners[name] = callback; },
      replaceChildren(...children) { this.children = children; }, appendChild(child) { this.children.push(child); },
      set innerHTML(value) { throw new Error('Unsafe HTML sink: ' + value); } };
  }
  const get = id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); };
  const calls = [];
  let keyNumber = 0;
  const context = vm.createContext({
    console, Intl, Date,
    crypto: { randomUUID: () => `client-attempt-${++keyNumber}` },
    sessionStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    localStorage: { setItem() {}, removeItem() {} },
    document: { getElementById: get, createElement: element,
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-token' }),
      addEventListener: (name, callback) => listeners.set(name, callback) },
    fetch: async (url, options = {}) => {
      calls.push({ url, ...options });
      if (url === '/cart/checkout') return checkoutResponse();
      if (url === '/cart/quote') return quoteResponse();
      if (url === '/cart/add' || url.startsWith('/cart/remove/')) return cartMutationResponse();
      if (url === '/cart/view') return cartViewResponse();
      return { ok: true, json: async () => true, text: async () => '{"items":[]}' };
    }
  });
  vm.runInContext(script, context);
  return { context, elements, get, calls, storage,
    start: async () => { listeners.get('DOMContentLoaded')(); await new Promise(setImmediate); },
    quote: () => vm.runInContext('requestQuote()', context),
    add: () => vm.runInContext('aggiungiProdotto(1)', context),
    remove: () => vm.runInContext('rimuoviProdotto(1)', context),
    confirm: () => { get('confirmPrices').checked = true; return vm.runInContext('submitCheckout({ preventDefault() {} })', context); },
    submit: () => vm.runInContext('submitCheckout({ preventDefault() {} })', context) };
}

test('displays signed server totals safely and requires explicit checkbox confirmation', async () => {
  const p = page(); await p.quote();
  assert.equal(p.get('quote-lines').children[0].textContent.includes('<script>untrusted name</script>'), true);
  await p.submit();
  assert.equal(p.calls.filter(call => call.url === '/cart/checkout').length, 0);
  await p.confirm();
  const request = p.calls.find(call => call.url === '/cart/checkout');
  assert.equal(request.headers['X-CSRF-TOKEN'], 'csrf-token');
  assert.deepEqual(JSON.parse(request.body), { quoteToken: quote.quoteToken, confirmPrices: true });
  assert.equal(p.storage.size, 0);
  assert.match(p.get('checkout-status').textContent, /42/);
});

test('ambiguous network failure retains exact key and request across a page reload', async () => {
  const storage = new Map();
  const first = page({ storage, checkoutResponse: async () => { throw new Error('network unavailable'); } });
  await first.quote(); await first.confirm();
  assert.equal(storage.size, 1);
  const sent = first.calls.find(call => call.url === '/cart/checkout');
  const retry = page({ storage }); await retry.start();
  assert.equal(retry.get('quoteButton').disabled, true);
  await retry.confirm();
  const resent = retry.calls.find(call => call.url === '/cart/checkout');
  assert.equal(resent.headers['Idempotency-Key'], sent.headers['Idempotency-Key']);
  assert.equal(resent.body, sent.body);
  assert.equal(storage.size, 0);
});

test('conflict invalidates confirmation and requires a fresh quote', async () => {
  const p = page({ checkoutResponse: async () => ({ ok: false, status: 409 }) });
  await p.quote(); await p.confirm();
  assert.equal(p.storage.size, 0);
  assert.equal(p.get('confirmPrices').disabled, true);
  assert.equal(p.get('paymentButton').disabled, true);
  assert.equal(p.get('quoteButton').disabled, false);
  await p.confirm();
  assert.equal(p.calls.filter(call => call.url === '/cart/checkout').length, 1);
});

test('does not send a checkout when it cannot retain the retry key', async () => {
  const p = page(); await p.quote();
  vm.runInContext('sessionStorage.setItem = () => { throw new Error("storage unavailable"); }', p.context);
  await p.confirm();
  assert.equal(p.calls.filter(call => call.url === '/cart/checkout').length, 0);
});


test('signed-price display preserves the full DECIMAL(19,2) range from numeric JSON', () => {
  const p = page();
  const result = vm.runInContext(`parseExactJson('{"total":99999999999999999.99,"items":[{"unitPrice":99999999999999999.99}],"name":"Jig 123"}')`, p.context);
  assert.equal(result.total, '99999999999999999.99');
  assert.equal(result.items[0].unitPrice, '99999999999999999.99');
  assert.equal(result.name, 'Jig 123');
  assert.equal(vm.runInContext('money("99999999999999999.99")', p.context), '99.999.999.999.999.999,99 EUR');
});


for (const status of [401, 403, 408, 429]) {
  test(`retains pending key after ambiguous failure followed by HTTP ${status}`, async () => {
    let attempt = 0;
    const p = page({ checkoutResponse: async () => {
      attempt++;
      if (attempt === 1) throw new Error('response lost');
      if (attempt === 2) return { ok: false, status };
      return { ok: true, text: async () => '{"id":42,"total":0.40}' };
    } });
    await p.quote(); await p.confirm(); await p.confirm();
    assert.equal(p.storage.size, 1);
    assert.equal(p.get('quoteButton').disabled, true);
    await p.confirm();
    const calls = p.calls.filter(call => call.url === '/cart/checkout');
    assert.equal(calls.length, 3);
    assert.equal(new Set(calls.map(call => call.headers['Idempotency-Key'])).size, 1);
    assert.equal(new Set(calls.map(call => call.body)).size, 1);
    assert.equal(p.storage.size, 0);
  });
}

test('catalog markup and image URLs never become HTML or inline handlers', () => {
  const p = page();
  const attack = '<img src=x onerror="window.stolen=true">';
  p.context.catalog = { items: [{ id: '9007199254740993', nome: attack, categoria: attack,
    descrizione: attack, image: 'javascript:alert(1)', prezzo: '99999999999999999.99', quantita: '1' }] };
  vm.runInContext('renderCart(catalog)', p.context);
  const article = p.get('carrello-container').children[0];
  assert.equal(article.children[0].src, '/images/product-placeholder.svg');
  const details = article.children[1];
  assert.equal(details.children[0].textContent, attack);
  assert.equal(details.children[1].textContent, attack);
  assert.equal(details.children[2].textContent, attack);
  const remove = details.children[3].children[0];
  assert.equal(remove.attributes.onclick, undefined);
  assert.equal(typeof remove.listeners.click, 'function');
  assert.equal(p.get('totale').textContent, '99999999999999999.99');
});

test('cart accepts a managed UUID image and falls back when the file is unavailable', () => {
  const p = page();
  const url = '/media/products/12345678-1234-1234-1234-123456789abc.png';
  p.context.catalog = {items: [{id: '7', nome: 'Esca', image: url, prezzo: '10.00', quantita: '1'}]};
  vm.runInContext('renderCart(catalog)', p.context);
  const image = p.get('carrello-container').children[0].children[0];
  assert.equal(image.src, url);
  image.listeners.error();
  assert.equal(image.src, '/images/product-placeholder.svg');
});

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
for (const operation of ['add', 'remove']) {
  test(`blocks quotes and checkout throughout ${operation} and cart refresh`, async () => {
    const mutation = deferred(), view = deferred();
    const p = page({ cartMutationResponse: () => mutation.promise, cartViewResponse: () => view.promise });
    await p.quote();
    const changing = p[operation]();
    assert.equal(p.get('quoteButton').disabled, true);
    assert.equal(p.get('confirmPrices').disabled, true);
    await p.quote(); await p.confirm();
    assert.equal(p.calls.filter(c => c.url === '/cart/quote').length, 1);
    assert.equal(p.calls.filter(c => c.url === '/cart/checkout').length, 0);
    mutation.resolve({ ok: true }); await new Promise(setImmediate);
    await p.quote();
    assert.equal(p.get('quoteButton').disabled, true);
    assert.equal(p.calls.filter(c => c.url === '/cart/quote').length, 1);
    view.resolve({ ok: true, text: async () => '{"items":[]}' }); await changing;
    assert.equal(p.get('quoteButton').disabled, false);
    await p.quote();
    assert.equal(p.calls.filter(c => c.url === '/cart/quote').length, 2);
    const sent = p.calls.find(c => c.url === '/cart/add' || c.url.startsWith('/cart/remove/'));
    assert.equal(sent.headers['X-CSRF-TOKEN'], 'csrf-token');
  });
}
test('blocks cart mutations and duplicate quote requests while a quote is in flight', async () => {
  const response = deferred(); const p = page({ quoteResponse: () => response.promise });
  const quoting = p.quote(); await p.add(); await p.remove(); await p.quote();
  assert.equal(p.calls.length, 1);
  assert.equal(p.get('quoteButton').disabled, true);
  response.resolve({ ok: true, text: async () => JSON.stringify(quote) }); await quoting;
  assert.equal(p.get('quoteButton').disabled, false);
  await p.add();
  assert.equal(p.get('confirmPrices').disabled, true);
  assert.equal(p.get('price-confirmation').hidden, true);
});
test('failed mutation releases the quote guard but never restores stale confirmation', async () => {
  const p = page({ cartMutationResponse: async () => { throw new Error('network failure'); } });
  await p.quote(); await p.remove();
  assert.equal(p.get('quoteButton').disabled, false);
  assert.equal(p.get('confirmPrices').disabled, true);
  assert.match(p.get('checkout-status').textContent, /network failure/);
  await p.quote(); assert.equal(p.calls.filter(c => c.url === '/cart/quote').length, 2);
});
