import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const html = fs.readFileSync(path.join(root, 'android-native/app/src/main/assets/shop-map.html'), 'utf8');
const nodes = new Map();
const node = () => ({style: {}, replaceChildren() {}, append() {}, addEventListener() {}, setPointerCapture() {}});
const context = vm.createContext({
  document: {getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); }, createElement: node},
  innerWidth: 390, innerHeight: 500, addEventListener() {}
});
vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
assert.equal(vm.runInContext('getLocation()', context), null, 'untouched default position must not save');
for (const point of [[39.9,116.4],[31.2,121.5],[0,0],[-33.9,151.2],[40.7,-74]]) {
  const actual = vm.runInContext('from(...world(' + point[0] + ',' + point[1] + '))', context);
  assert.ok(Math.abs(actual[0] - point[0]) < 1e-8 && Math.abs(actual[1] - point[1]) < 1e-8, 'map projection round trip: ' + point);
}
vm.runInContext('setLocation(31.2,121.5)', context);
assert.equal(vm.runInContext('getLocation().longitude', context), 121.5);
assert.equal(vm.runInContext('getLocation().coordinate_system', context), 'wgs84');

const java = fs.readFileSync(path.join(root, 'android-native/app/src/main/java/com/spx/chat/MainActivity.java'), 'utf8');
const region = java.slice(java.indexOf('private JSONArray mallUrlLines'), java.indexOf('private EditText mallUploadTarget'));
const strings = [...region.matchAll(/\.split\(("(?:\\.|[^"\\])*")/g)].map(m => JSON.parse(m[1]));
assert.ok(strings.length >= 1);
assert.deepEqual('https://a.test/1.jpg\r\nhttps://a.test/2.jpg'.split(new RegExp(strings[0])), ['https://a.test/1.jpg','https://a.test/2.jpg']);
console.log('PASS client checks: untouched map selection, projection round trips, coordinate source and legacy image URL parsing');
