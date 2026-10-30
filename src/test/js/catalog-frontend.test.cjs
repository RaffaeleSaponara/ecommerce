const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/prodotti.html'), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)][1][1];
const tick = () => new Promise(setImmediate);
const product = {id: 1, nome: 'Esca', categoria: 'Hardbaits', prezzo: '10.00', image: '/images/b1.jpg'};

function page() {
  function element() {
    return {value: '', children: [], listeners: {}, dataset: {}, textContent: '',
      appendChild(child) { this.children.push(child); },
      replaceChildren(...children) { this.children = children; },
      addEventListener(name, fn) { this.listeners[name] = fn; },
      setAttribute(name, value) { this[name] = value; },
      removeAttribute(name) { delete this[name]; },
      set innerHTML(value) { throw Error('Unsafe HTML sink: ' + value); }
    };
  }
  const ids = Object.fromEntries(['catalog-filters', 'filter-q', 'catalog-status'].map(id => [id, element()]));
  const container = element(), pending = [], calls = [];
  let ready;
  const context = vm.createContext({URLSearchParams, console: {error() {}},
    document: {createElement: element, getElementById: id => ids[id], querySelector: () => container,
      addEventListener: (_, callback) => { ready = callback; }},
    fetch: url => { calls.push(url); return new Promise((resolve, reject) => pending.push({resolve, reject})); }
  });
  vm.runInContext(script, context);
  return {ids, container, pending, calls, start: () => ready(),
    submit: () => ids['catalog-filters'].listeners.submit({preventDefault() {}}),
    async respond(data, index = 0) { pending.splice(index, 1)[0].resolve({ok: true, json: async () => data}); await tick(); }
  };
}

test('prices retain every cent at DECIMAL(19,2) limit from raw JSON', async () => {
  const p = page(); p.start();
  p.pending.shift().resolve({ok: true, text: async () => '[{"id":1,"nome":"Esca","categoria":"Hardbaits","prezzo":99999999999999999.99}]'});
  await tick();
  assert.equal(p.container.children[0].children[1].children[3].children[0].textContent, '€99999999999999999.99');
});
