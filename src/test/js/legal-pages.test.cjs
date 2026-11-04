const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const templates=path.resolve(__dirname,'../../main/resources/templates');
test('pages do not automatically contact Google Fonts and expose legal navigation',()=>{
  for(const name of fs.readdirSync(templates).filter(n=>n.endsWith('.html'))){
    const html=fs.readFileSync(path.join(templates,name),'utf8');
    assert.doesNotMatch(html,/fonts\.(googleapis|gstatic)\.com/,name);
    assert.match(html,/fragments\/legal :: links/,name);
  }
});
test('registration and checkout disclose draft information without a privacy consent checkbox',()=>{
  const registration=fs.readFileSync(path.join(templates,'registrazione.html'),'utf8');
  const cart=fs.readFileSync(path.join(templates,'cart.html'),'utf8');
  assert.match(registration,/href="\/privacy"/);
  assert.match(cart,/Ordina con obbligo di pagare/);
  for(const route of ['/privacy','/termini','/spedizioni-resi'])assert.ok(cart.includes(`href="${route}"`));
  assert.match(cart,/14 giorni/);assert.match(cart,/due anni/);assert.match(cart,/DA COMPLETARE/);
});
