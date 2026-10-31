const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const code = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/addresses.js'), 'utf8');
const address = { id: 17, recipientName: '<img src=x onerror=alert(1)>', street: 'Via Roma', houseNumber: '3', postalCode: '00100', municipality: 'Roma', province: 'RM', country: 'IT', deliveryNotes: '<script>notes</script>', defaultAddress: true };
function page({ allowed = true, failure = false } = {}) {
  function el(tag) {
    return { tag, children: [], listeners: {}, value: '', checked: false, disabled: false, textContent: '',
      appendChild(n) { this.children.push(n); }, prepend(n) { this.children.unshift(n); },
      replaceChildren(...nodes) { this.children = nodes; }, setAttribute(k, v) { this[k] = v; },
      addEventListener(k, fn) { this.listeners[k] = fn; }, focus() {}, reset() {}, reportValidity() { return true; },
      set innerHTML(v) { throw new Error('Unsafe HTML: ' + v); }
    };
  }
  const root = el('div'), calls = [], changes = [], busy = [];
  const context = vm.createContext({ console,
    window: { confirm: () => true },
    document: { createElement: el, getElementById: () => root,
      querySelector: s => ({ content: s.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-value' }) },
    fetch: async (url, options = {}) => {
      calls.push({ url, ...options });
      if (failure) return { ok: false, json: async () => ({ message: 'Sessione scaduta' }) };
      return { ok: true, json: async () => options.method ? address : [address] };
    },
    options: { canMutate: () => allowed, onChange: data => changes.push(data), onBusy: b => busy.push(b) }
  });
  vm.runInContext(code, context);
  return { root, calls, changes, busy, mount: () => vm.runInContext('AddressBook.mount(options)', context) };
}
function input(form, name) { return form.children.flatMap(n => n.children || []).find(n => n.name === name); }

test('address book renders untrusted names and notes only through textContent', async () => {
  const p = page(); await p.mount();
  const card = p.root.children[1].children[0];
  assert.equal(card.children[0].textContent, address.recipientName + ' (predefinito)');
  assert.equal(card.children[2].textContent, address.deliveryNotes);
  assert.equal(p.changes[0][0].id, 17);
});

test('creation sends trimmed fields, fixed Italy and CSRF, then refreshes choices', async () => {
  const p = page(); await p.mount();
  const form = p.root.children[2];
  for (const name of ['recipientName', 'street', 'houseNumber', 'postalCode', 'municipality', 'province', 'deliveryNotes']) input(form, name).value = ' ' + address[name] + ' ';
  input(form, 'province').value = ' rm ';
  await form.listeners.submit({ preventDefault() {} });
  const sent = p.calls.find(c => c.method === 'POST');
  assert.equal(sent.url, '/api/addresses');
  assert.equal(sent.headers['X-CSRF-TOKEN'], 'csrf-value');
  const data = JSON.parse(sent.body);
  assert.equal(data.country, 'IT'); assert.equal(data.province, 'RM'); assert.equal(data.postalCode, '00100');
  assert.equal(data.recipientName, address.recipientName);
  assert.equal(p.changes.length, 2); assert.deepEqual(p.busy, [true, false]);
});

test('editing and deletion target address endpoints with CSRF', async () => {
  const p = page(); await p.mount();
  let card = p.root.children[1].children[0];
  card.children.find(n => n.textContent === 'Modifica').listeners.click();
  await p.root.children[2].listeners.submit({ preventDefault() {} });
  assert.equal(p.calls.find(c => c.method === 'PUT').url, '/api/addresses/17');
  card = p.root.children[1].children[0];
  card.children.find(n => n.textContent === 'Elimina').listeners.click();
  await new Promise(setImmediate);
  assert.equal(p.calls.find(c => c.method === 'DELETE').headers['X-CSRF-TOKEN'], 'csrf-value');
});

test('pending checkout guard blocks all book mutations and edit state', async () => {
  const p = page({ allowed: false }); await p.mount();
  const form = p.root.children[2], card = p.root.children[1].children[0];
  card.children.find(n => n.textContent === 'Modifica').listeners.click();
  card.children.find(n => n.textContent === 'Elimina').listeners.click();
  await form.listeners.submit({ preventDefault() {} });
  assert.equal(p.calls.length, 1); assert.equal(form.children[0].textContent, 'Nuovo indirizzo');
});

test('API failure is shown in an accessible live status', async () => {
  const p = page({ failure: true }); await p.mount();
  assert.equal(p.root.children[0].textContent, 'Sessione scaduta');
  assert.equal(p.root.children[0].role, 'status');
});
