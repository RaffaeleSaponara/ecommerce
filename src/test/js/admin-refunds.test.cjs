const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/admin-refunds.js','utf8');
function page({enabled=true,confirm=true,state={paymentStatus:'SUCCEEDED',refundStatus:null,canCancel:true,canRefundLate:false,amount:18.4,anomalies:0},request}={}) {
  const elements=new Map(),calls=[];let ready;
  const element=()=>({children:[],textContent:'',disabled:false,listeners:{},appendChild(c){this.children.push(c);},replaceChildren(...c){this.children=c;},querySelectorAll(){return this.children;},remove(){this.removed=true;},addEventListener(k,f){this.listeners[k]=f;},set innerHTML(v){throw Error('Unsafe HTML');}});
  const get=id=>{if(!enabled)return null;if(!elements.has(id))elements.set(id,element());return elements.get(id);};
  const context={document:{getElementById:get,createElement:element,addEventListener:(n,f)=>{ready=f;}},window:{confirm:()=>confirm},
    money:v=>String(v)+' EUR',showOrder:async id=>{calls.push({showOrder:id});},
    adminRequest:async(url,options)=>{calls.push({url,options});if(request)return request(url,options);if(url.endsWith('/refunds')||url.endsWith('/anomalies'))return [];return state;}};
  vm.runInNewContext(source,context);
  return {context,get,calls,ready:()=>ready()};
}
test('disabled financial panel makes no requests',async()=>{
  const p=page({enabled:false});p.ready();await p.context.window.showOrderFinancials(7);assert.equal(p.calls.length,0);
});
test('financial data and diagnostic values are rendered only as text',async()=>{
  const malicious='<img src=x onerror=alert(1)>';
  const p=page({state:{paymentStatus:'EXPIRED',refundStatus:'FAILED',issue:malicious,anomalies:1}});
  await p.context.window.showOrderFinancials(7);
  assert.ok(p.get('stripe-financial-status').textContent.includes(malicious));
  assert.equal(p.get('stripe-financial-actions').children.length,0);
});
test('confirmation is mandatory before requesting a refund',async()=>{
  const p=page({confirm:false});await p.context.window.showOrderFinancials(7);
  await p.get('stripe-financial-actions').children[0].listeners.click();
  assert.equal(p.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('double click produces one protected admin command and refreshes persisted state',async()=>{
  let finish;
  const p=page({request:async(url,options)=>{
    if(options?.method==='POST')return new Promise(resolve=>{finish=resolve;});
    if(url.endsWith('/refunds')||url.endsWith('/anomalies'))return [];
    return {paymentStatus:'SUCCEEDED',canCancel:true,amount:18.4,anomalies:0};
  }});
  await p.context.window.showOrderFinancials(7);const button=p.get('stripe-financial-actions').children[0];
  const first=button.listeners.click();await button.listeners.click();
  const posts=p.calls.filter(c=>c.options?.method==='POST');assert.equal(posts.length,1);
  assert.equal(posts[0].url,'/stripe/orders/7/cancel-refund');assert.deepEqual(JSON.parse(posts[0].options.body),{confirmed:true});
  finish({});await first;assert.ok(p.calls.some(c=>c.showOrder===7));
});
test('late refund has a distinct command and failures remain visible',async()=>{
  const p=page({request:async(url,options)=>{
    if(options?.method==='POST')throw Error('Incasso non verificato');
    if(url.endsWith('/refunds')||url.endsWith('/anomalies'))return [];
    return {paymentStatus:'EXPIRED',canRefundLate:true,amount:18.4,anomalies:1};
  }});
  await p.context.window.showOrderFinancials(9);await p.get('stripe-financial-actions').children[0].listeners.click();
  assert.equal(p.calls.find(c=>c.options?.method==='POST').url,'/stripe/orders/9/refund');
  assert.equal(p.get('stripe-financial-status').textContent,'Incasso non verificato');
});
