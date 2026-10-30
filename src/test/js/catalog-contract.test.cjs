const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const html=fs.readFileSync(path.resolve(__dirname,'../../main/resources/templates/prodotti.html'),'utf8');
const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
const product={id:1,nome:'Alpha',categoria:'Hardbaits',prezzo:10,image:'/images/b1.jpg',descrizione:'Esca'};
const tick=()=>new Promise(setImmediate);
function page() {
  function element(tag) { return {tag,className:'',textContent:'',value:'',children:[],dataset:{},listeners:{},style:{},disabled:false,
    appendChild(c){this.children.push(c);return c;}, append(...c){this.children.push(...c);},replaceChildren(...c){this.children=c;},
    addEventListener(n,f){this.listeners[n]=f;},setAttribute(n,v){this[n]=v;},removeAttribute(n){delete this[n];},
    set innerHTML(v){throw Error('Unsafe HTML: '+v);} }; }
  const ids=Object.fromEntries(['catalog-filters','filter-q','catalog-status'].map(id=>[id,element(id)]));
  const container=element('section'), calls=[], pending=[], ready=[], timers=new Map();
  let timerId=0;
  const document={createElement:element,getElementById:id=>ids[id],addEventListener:(n,f)=>ready.push(f),
    querySelector:s=>s==='.products'?container:s.startsWith('#')?ids[s.slice(1)]:{content:'csrf'}};
  const context=vm.createContext({document,URLSearchParams,AbortController,console:{error(){}},
    setTimeout:(fn,delay)=>{timers.set(++timerId,{fn,delay});return timerId;},clearTimeout:id=>timers.delete(id),
    fetch:(url,options)=>{calls.push({url,options});return new Promise((resolve,reject)=>pending.push({resolve,reject}));}});
  vm.runInContext(scripts[1],context);
  const respond=async(data,ok=true,index=0)=>{pending.splice(index,1)[0].resolve({ok,status:ok?200:400,json:async()=>data});await tick();};
  const submit=()=>ids['catalog-filters'].listeners.submit({preventDefault(){}});
  const type=(value,isComposing=false)=>{ids['filter-q'].value=value;ids['filter-q'].listeners.input({isComposing});};
  const flush=()=>{const queued=[...timers.values()];timers.clear();queued.forEach(t=>t.fn());};
  return {ids,container,calls,pending,respond,submit,type,flush,timers,start:()=>ready.forEach(f=>f())};
}
test('search shows only the name input, without category, prices or buttons',()=>{
  const form=html.match(/<form id="catalog-filters"[\s\S]*?<\/form>/)[0];
  assert.match(form,/role="search"/);assert.match(form,/label for="filter-q"/);
  assert.equal([...form.matchAll(/<input\b/g)].length,1);
  assert.doesNotMatch(form,/<select\b|<button\b|filter-category|filter-min|filter-max/);
});
test('typing debounces requests and sends only the trimmed name; no results are visible',async()=>{
  const p=page();p.start();await p.respond([product,{...product,id:2,categoria:'Softbaits'}]);
  assert.equal(p.calls[0].url,'/prodotti/all');assert.equal(p.container.children.length,2);
  p.type('A');p.type('Al');p.type(' Alpha ');
  assert.equal(p.calls.length,1);assert.equal(p.timers.size,1);assert.equal([...p.timers.values()][0].delay,250);
  p.flush();const url=new URL(p.calls.at(-1).url,'http://localhost');
  assert.equal(url.pathname,'/prodotti/all');assert.equal(url.search,'?q=Alpha');
  await p.respond([]);assert.match(p.ids['catalog-status'].textContent,/nessun/i);
  assert.equal(p.container.children.length,0);
});
test('clearing the search restores the complete catalog',async()=>{
  const p=page();p.start();await p.respond([product]);
  p.type('missing');p.flush();await p.respond([]);p.type('');p.flush();
  assert.equal(p.calls.at(-1).url,'/prodotti/all');await p.respond([product]);assert.equal(p.container.children.length,1);
});
test('HTTP and network failures are visible and a new search recovers',async()=>{
  const p=page();p.start();await p.respond({message:'Servizio non disponibile'},false);
  assert.match(p.ids['catalog-status'].textContent,/Servizio/);
  p.type('Alpha');p.flush();p.pending.shift().reject(Error('Connessione assente'));await tick();
  assert.match(p.ids['catalog-status'].textContent,/Connessione/);assert.equal(p.container['aria-busy'],'false');
  p.type('Jig');p.flush();await p.respond([product]);assert.equal(p.container.children.length,1);
});
test('obsolete responses cannot replace results during debounce or after newer requests',async()=>{
  const p=page();p.start();await p.respond([product]);p.type('old');p.flush();p.type('new');
  await p.respond([{...product,nome:'Obsolete during debounce'}]);
  assert.equal(p.container.children[0].children[1].children[1].children[0].textContent,'Alpha');
  p.flush();p.type('latest');p.flush();
  await p.respond([{...product,nome:'New result'}],true,1);await p.respond([{...product,nome:'Old result'}]);
  const body=p.container.children[0].children[1];assert.equal(body.children[1].children[0].textContent,'New result');
});
test('Enter searches immediately and cancels the scheduled duplicate request',async()=>{
  const p=page();p.start();await p.respond([product]);p.type('%_ &');p.submit();
  assert.equal(new URL(p.calls.at(-1).url,'http://localhost').searchParams.get('q'),'%_ &');
  assert.equal(p.timers.size,0);p.flush();assert.equal(p.calls.length,2);await p.respond([]);
});
test('composition waits until the user has completed the input',async()=>{
  const p=page();p.start();await p.respond([product]);p.type('字',true);
  assert.equal(p.timers.size,0);assert.equal(p.calls.length,1);
  p.ids['filter-q'].listeners.compositionend({});p.flush();
  assert.equal(new URL(p.calls.at(-1).url,'http://localhost').searchParams.get('q'),'字');await p.respond([]);
});
