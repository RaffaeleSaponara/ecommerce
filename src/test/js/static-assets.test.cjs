const { test }=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const base=path.resolve(__dirname,'../../main/resources');
test('all template CSS references resolve with exact casing on case-sensitive filesystems',()=>{
  let checked=0;
  for(const file of fs.readdirSync(path.join(base,'templates')).filter(file=>file.endsWith('.html'))) {
    const html=fs.readFileSync(path.join(base,'templates',file),'utf8');
    for(const match of html.matchAll(/\/(?:css|CSS)\/[A-Za-z0-9_.-]+\.css/g)) {
      const segments=match[0].slice(1).split('/');let directory=path.join(base,'static');
      for(const segment of segments) {
        assert.ok(fs.readdirSync(directory).includes(segment),`${file}: missing exact-case resource ${match[0]}`);
        directory=path.join(directory,segment);
      }
      checked++;
    }
  }
  assert.ok(checked>=4);
});
