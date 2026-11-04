const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/admin-product-information.js','utf8');
function page(){
  const events={},calls=[],elements={};
  for(const name of ['productId','modelCode','identification','materials','dimensions','manufacturerName','manufacturerAddress','manufacturerEmail','warnings','instructions','weightGrams','verified'])elements[name]={value:'',checked:false};
  elements.productId.value='7';
  const button={},status={textContent:''},load={addEventListener:(name,fn)=>events.load=fn};
  const form={elements,reportValidity:()=>true,querySelector:()=>button,addEventListener:(name,fn)=>events.submit=fn};
  const document={getElementById:id=>({'technical-form':form,'technical-status':status,'technical-load':load}[id]),addEventListener:(name,fn)=>fn()};
  vm.runInNewContext(source,{document,adminRequest:async(path,options)=>{calls.push({path,options});return {data:{modelCode:'FIXTURE',verified:false},missing:['materials','warnings']};}});
  return {events,calls,elements,status,button};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
test('technical editor requires loading the selected product and exposes missing facts',async()=>{
  const p=page();p.events.submit({preventDefault(){}});await settle();assert.equal(p.calls.length,0);
  p.events.load();await settle();assert.equal(p.calls[0].path,'/products/7/information');assert.match(p.status.textContent,/materials, warnings/);
  p.elements.productId.value='8';p.events.submit({preventDefault(){}});await settle();assert.equal(p.calls.length,1);
  assert.match(p.status.textContent,/Carica prima/);
});
test('technical editor sends an explicit verification flag and unlocks after save',async()=>{
  const p=page();p.events.load();await settle();p.elements.verified.checked=true;
  p.events.submit({preventDefault(){}});await settle();
  assert.equal(p.calls[1].options.method,'PUT');assert.equal(JSON.parse(p.calls[1].options.body).verified,true);
  assert.equal(p.button.disabled,false);assert.equal(p.elements.productId.disabled,false);
});
