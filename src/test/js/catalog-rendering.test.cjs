const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname,'../../main/resources/templates/prodotti.html'),'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match=>match[1]);
function page(response={ok:true,json:async()=>[]}) {
  function element(tag) {
    return { tag, className:'', textContent:'', value:'', children:[], dataset:{}, listeners:{},
      appendChild(child){this.children.push(child);}, replaceChildren(){this.children=[];},
      setAttribute(name,value){this[name]=value;},removeAttribute(name){delete this[name];},
      reset(){for(const id of ['filter-q','filter-category','filter-min','filter-max'])ids[id].value='';},
      addEventListener(name,callback){this.listeners[name]=callback;},
      set innerHTML(value){throw new Error('Unsafe HTML sink: '+value);} };
  }
  const container=element('section'); const calls=[], errors=[], listeners={};
  const ids=Object.fromEntries(['catalog-filters','filter-q','filter-category','filter-min','filter-max','reset-filters','catalog-status'].map(id=>[id,element(id)]));
  const context=vm.createContext({console:{error:(...args)=>errors.push(args)},setTimeout:fn=>fn(),URLSearchParams,
    document:{createElement:element,getElementById:id=>ids[id],querySelector:selector=>selector==='.products'?container:{content:selector.includes('_csrf_header')?'X-CSRF-TOKEN':'csrf-value'},
      addEventListener:(name,handler)=>{listeners[name]=handler;}},
    fetch:async(url,options)=>{calls.push({url,options});return response;}});
  vm.runInContext(scripts[1],context);
  return {context,container,calls,errors,listeners,render:products=>{context.products=products;vm.runInContext('renderCatalog(products)',context);}};
}
const product={id:7,nome:'Jig',descrizione:'Description',categoria:'Hardbaits',prezzo:12.5,image:'/images/b1.jpg',stock:5};
test('catalog treats malicious names, descriptions and categories as text',()=>{
  const p=page(); const markup='<img src=x onerror="window.stolen=true">';
  p.render([{...product,nome:markup,descrizione:markup,categoria:markup,image:'javascript:alert(1)'}]);
  const article=p.container.children[0]; const body=article.children[1];
  assert.equal(article.children[0].children[0].src,'/images/product-placeholder.svg');
  assert.equal(article.children[0].children[0].alt,markup);
  assert.deepEqual(body.children.slice(0,3).map(child=>child.tag === 'h2' ? child.children[0].textContent : child.textContent),[markup,markup,markup]);
  assert.equal(body.children[3].children[1].onclick,undefined);
});
test('catalog keeps card classes, image, price and CSRF add behavior',async()=>{
  const p=page();p.render([product]);
  const article=p.container.children[0], body=article.children[1], button=body.children[3].children[1];
  assert.equal(article.className,'product');assert.equal(article.children[0].className,'product-media');
  assert.equal(article.children[0].href,'/prodotti/7');
  assert.equal(body.children[1].children[0].href,'/prodotti/7');
  assert.equal(body.className,'product-body');assert.equal(body.children[3].className,'product-footer');
  assert.equal(body.children[3].children[0].textContent,'\u20ac12.50');
  assert.equal(button.dataset.id,'7');assert.equal(button.type,'button');
  button.listeners.click();await new Promise(setImmediate);
  assert.equal(p.calls[0].url,'/cart/add');assert.equal(p.calls[0].options.headers['X-CSRF-TOKEN'],'csrf-value');
  assert.equal(p.calls[0].options.body,'7');
});

test('catalog allows only UUID managed images and safe local legacy assets',()=>{
  const p=page();
  const url='/media/products/12345678-1234-1234-1234-123456789abc.png';
  p.render([{...product,image:url}]);
  assert.equal(p.container.children[0].children[0].children[0].src,url);
  for(const image of ['/media/products/../../secret.png','//outside.invalid/image.png','/media/products/file.png','/images/../secret.png']) {
    p.render([{...product,image}]);
    assert.equal(p.container.children[0].children[0].children[0].src,'/images/product-placeholder.svg');
  }
});
test('catalog fetches and renders safely on DOM ready',async()=>{
  const p=page({ok:true,json:async()=>[product]});p.listeners.DOMContentLoaded();await new Promise(setImmediate);
  assert.equal(p.calls[0].url,'/prodotti/all');assert.equal(p.container.children.length,1);
});
test('catalog does not render failed HTTP responses or external image URLs',async()=>{
  const p=page({ok:false,json:async()=>{throw new Error('must not parse');}});
  p.listeners.DOMContentLoaded();await new Promise(setImmediate);
  assert.equal(p.errors.length,1);assert.equal(p.container.children.length,0);
  p.render([{...product,image:'https://outside.invalid/tracker.png'}]);
  assert.equal(p.container.children[0].children[0].children[0].src,'/images/product-placeholder.svg');
});

test('sold-out catalog products cannot be added and the cart limit disables further additions',async()=>{
  const p=page({ok:true,json:async()=>({items:[{id:7,stock:2,quantita:2}]})});
  p.render([{...product,stock:0}]);
  let body=p.container.children[0].children[1],button=body.children[3].children[1];
  assert.equal(button.disabled,true);assert.match(body.children[4].textContent,/Esaurito/);
  button.listeners.click();await new Promise(setImmediate);assert.equal(p.calls.length,0);
  p.render([{...product,stock:2}]);body=p.container.children[0].children[1];button=body.children[3].children[1];
  button.listeners.click();await new Promise(setImmediate);
  assert.equal(button.disabled,true);assert.match(body.children[4].textContent,/Quantità massima/);
});

test('catalog stock conflict shows the server message without suggesting a login failure',async()=>{
  const p=page({ok:false,status:409,json:async()=>({error:'Disponibilità insufficiente'})});p.render([product]);
  const body=p.container.children[0].children[1];body.children[3].children[1].listeners.click();await new Promise(setImmediate);
  assert.match(body.children[4].textContent,/Disponibilità insufficiente/);
  assert.doesNotMatch(body.children[4].textContent,/Login/);
});
