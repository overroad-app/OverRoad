// 사용: node render.mjs <url> <출력 폴더> [fps] [시각...]
//   시각을 주면 그 시각들의 정지 화면만, 아니면 전체 프레임을 뽑는다.
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
const [url, out, fpsArg, ...times] = process.argv.slice(2);
const fps = Number(fpsArg || 30);
fs.mkdirSync(out, { recursive: true });
const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: 'new', args: ['--use-angle=metal', '--enable-unsafe-swiftshader', '--hide-scrollbars'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1080, height: 1920, deviceScaleFactor: 1 });
page.on('pageerror', e => console.log('pageerror:', e.message));
await page.goto(url, { waitUntil: 'networkidle0' });
await page.waitForFunction('window.ready === true', { timeout: 90000 });
await page.evaluate(() => document.fonts.ready);
const list = times.length ? times.map(Number) : [...Array(Math.round((await page.evaluate('window.DURATION')) * fps)).keys()].map(i => i / fps);
let n = 0;
for (const t of list) {
  await page.evaluate(tt => window.renderAt(tt), t);
  const name = times.length ? `t_${t.toFixed(1)}.png` : `f${String(n).padStart(5, '0')}.png`;
  await page.screenshot({ path: `${out}/${name}`, type: 'png' });
  if (++n % 150 === 0) console.log('frames', n, '/', list.length);
}
await browser.close();
console.log('done', n);
