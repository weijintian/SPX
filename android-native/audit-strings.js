const fs = require('fs');
const path = require('path');
const res = path.join(__dirname, 'app', 'src', 'main', 'res');
const unescapeXml = text => text.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'");
const repair = text => Buffer.from(text, 'latin1').toString('utf8');
for (const dir of fs.readdirSync(res).filter(name => name.startsWith('values'))) {
  const source = fs.readFileSync(path.join(res, dir, 'strings.xml'), 'utf8');
  const strings = [...source.matchAll(/<string name="([^"]+)">([\s\S]*?)<\/string>/g)].map(([, name, value]) => [name, unescapeXml(value)]);
  const broken = strings.filter(([, value]) => /姝|鐧|銉|鞚|鎴|璇/.test(value));
  console.log(`${dir}: ${strings.length} keys, ${broken.length} suspect values`);
  if (dir === 'values-en') for (const [name, value] of broken) console.log(`${name} = ${repair(value)}`);
}
