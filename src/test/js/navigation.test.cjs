const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/navigation.js', 'utf8');
function page() {
  const events = {}, classes = new Set();
  let button;
  const panel = {classList: {add() {}}, addEventListener(type, fn) {events['panel-' + type] = fn;}, contains(el) {return el === panel;}};
  const nav = {classList: {add(c) {classes.add(c);}, toggle(c, on) {on ? classes.add(c) : classes.delete(c);}}, querySelector() {return panel;}, insertBefore(el) {button = el;}, addEventListener(type, fn) {events[type] = fn;}};
  const mobile = {matches: true, addEventListener(type, fn) {events.resize = fn;}};
  const document = {activeElement: null, querySelector() {return nav;}, addEventListener(type, fn) {events.ready = fn;}, createElement() {return {attrs: {}, setAttribute(k,v) {this.attrs[k]=v;}, getAttribute(k) {return this.attrs[k];}, addEventListener(type, fn) {events['button-' + type]=fn;}, focus() {this.focused=true;}};}};
  vm.runInNewContext(source, {document, window: {matchMedia() {return mobile;}}});
  events.ready();
  return {events, classes, panel, button, mobile, document};
}
test('navigation exposes accessible toggle and Escape restores focus', () => {
  const p=page();
  assert.equal(p.button.attrs['aria-controls'],'primary-menu');
  assert.equal(p.button.attrs['aria-expanded'],'false');
  p.events['button-click']();
  assert.equal(p.button.attrs['aria-expanded'],'true');
  assert.ok(p.classes.has('navigation-open'));
  p.events.keydown({key:'Escape'});
  assert.equal(p.button.attrs['aria-expanded'],'false');
  assert.equal(p.button.focused,true);
});
test('navigation closes on mobile links and preserves focus when resize hides panel', () => {
  const p=page();p.events['button-click']();
  p.events['panel-click']({target:{closest(){return {};}}});
  assert.equal(p.button.attrs['aria-expanded'],'false');
  p.document.activeElement=p.panel;p.events.resize();
  assert.equal(p.button.focused,true);
});
test('all page templates provide skip link, destination and shared navigation assets', () => {
  for(const file of fs.readdirSync('src/main/resources/templates')) {
    if(!file.endsWith('.html'))continue;
    const html=fs.readFileSync('src/main/resources/templates/'+file,'utf8');
    assert.ok(html.includes('href="#main-content"'),file);
    assert.ok(html.includes('id="main-content"'),file);
    assert.ok(html.includes('/js/navigation.js'),file);
    assert.ok(html.includes('/css/responsive.css'),file);
  }
});
