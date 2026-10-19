#!/usr/bin/env node
// =============================================================================
// 前端页面走查：真实浏览器渲染 + 自动登录 + 逐页截图 + 收集控制台/接口错误
//
//   NODE_PATH=<workspace>/node_modules node tool/walkthrough-frontend.cjs
//
// 为什么不用 agent-browser：它自带的 Chromium 需要额外下载几百 MB，
// 本机若缺 ms-playwright 缓存，open 会一直卡住且没有任何输出。
// 直接用 puppeteer-core 驱动本机 Edge 更轻、更快、零下载。
//
// 为什么是 .cjs 而不是 .mjs（两条连带要求）：
//   1. 脚本靠 NODE_PATH 找全局装的 puppeteer-core，
//      而 ESM 的模块解析**根本不看 NODE_PATH**（直接 ERR_MODULE_NOT_FOUND）。
//   2. CommonJS 没有顶层 await，所以整个流程必须包在 async IIFE 里。
// 别手贱改回 .mjs。
//
// 坑位：
//   - 本机有 HTTP_PROXY，Chrome 会把 localhost 也走代理 → 必须 --no-proxy-server
//   - SPA 有轮询，waitUntil:'networkidle0' 兜不住，改为固定 sleep
// =============================================================================
const puppeteer = require('puppeteer-core');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

const EDGE = process.env.WALK_EDGE
  || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const BASE = process.env.WALK_BASE || 'http://127.0.0.1:4173';
const USER = process.env.WALK_USER || 'admin';
const PASS = process.env.WALK_PASS || 'admin123';
const OUT = process.env.WALK_OUT || path.join(ROOT, 'tool', 'walkthrough-shots');

const PAGES = [
  { name: 'dashboard', path: '/dashboard' },
  { name: 'kb',        path: '/kb' },
  { name: 'kb-detail', path: '/kb/1' },
  { name: 'search',    path: '/search' },
  { name: 'chat',      path: '/chat' },
  { name: 'metrics',   path: '/metrics' },
  { name: 'admin',     path: '/admin' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const report = [];

  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--no-proxy-server', '--window-size=1440,900'],
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });

  // ---- 收集错误：控制台 + 页面异常 + 失败请求 ----
  const errors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`[console] ${m.text().slice(0, 300)}`);
  });
  page.on('pageerror', (e) => errors.push(`[pageerror] ${String(e).slice(0, 300)}`));
  page.on('requestfailed', (r) => {
    errors.push(`[reqfail] ${r.url().slice(0, 160)} :: ${r.failure() && r.failure().errorText}`);
  });
  // HTTP 4xx/5xx 不算 requestfailed，只会以 "Failed to load resource" 出现在 console 里、
  // 丢掉 URL —— 定位问题时等于瞎猜。这里显式把非 2xx 的**响应 URL + 状态码**记下来。
  page.on('response', (res) => {
    const s = res.status();
    if (s >= 400) errors.push(`[http ${s}] ${res.url().slice(0, 170)}`);
  });

  const shot = (name) => page.screenshot({ path: path.join(OUT, `${name}.png`) });

  // ============================ 1. 登录 ============================
  console.log(`打开 ${BASE} ...`);
  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await sleep(1500);
  console.log(`  当前 URL: ${page.url()}`);

  const inputSel = 'input[type="text"], input[name="username"], input:not([type="password"])';
  const passSel = 'input[type="password"]';
  await page.waitForSelector(inputSel, { timeout: 20000 });
  await page.waitForSelector(passSel, { timeout: 20000 });

  await page.type(inputSel, USER);
  await page.type(passSel, PASS);
  await shot('00-login-filled');

  const btnClicked = await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('button'))
      .find((b) => /登\s*录|login/i.test(b.innerText));
    if (btn) { btn.click(); return btn.innerText.trim(); }
    return null;
  });
  console.log(`  点击按钮: ${btnClicked || '(未找到，改用回车)'}`);
  if (!btnClicked) await page.keyboard.press('Enter');

  await sleep(4000);
  const afterLogin = page.url();
  await shot('01-after-login');

  const loggedIn = !/\/login/.test(afterLogin);
  console.log(`  登录后 URL: ${afterLogin}`);
  console.log(`  登录结果: ${loggedIn ? '成功' : '失败'}`);
  report.push({ page: 'login', ok: loggedIn, note: afterLogin.replace(BASE, '') });

  if (!loggedIn) {
    console.log('\n登录失败，后续页面跳过。收集到的错误：');
    errors.slice(0, 20).forEach((e) => console.log('  ' + e));
    await browser.close();
    process.exit(1);
  }

  // ============================ 2. 逐页走查 ============================
  PAGES.forEach((p, i) => { p._idx = i + 10; });

  for (const p of PAGES) {
    errors.length = 0;
    console.log(`\n--- ${p.name} (${p.path}) ---`);
    try {
      await page.goto(`${BASE}${p.path}`, { waitUntil: 'domcontentloaded' });
      await sleep(2800);

      const info = await page.evaluate(() => {
        const bodyText = document.body.innerText || '';
        return {
          textLen: bodyText.trim().length,
          firstLines: bodyText.trim().split('\n').filter(Boolean).slice(0, 6),
          hasError: /出错了|报错|加载失败|Something went wrong|404/i.test(bodyText),
          title: document.title,
        };
      });

      await shot(`${String(p._idx).padStart(2, '0')}-${p.name}`);

      const ok = info.textLen > 30 && !info.hasError && errors.length === 0;
      console.log(`  文本长度: ${info.textLen} | 标题: ${info.title}`);
      console.log(`  首行: ${info.firstLines.slice(0, 3).join(' / ').slice(0, 130)}`);
      if (info.hasError) console.log('  ⚠ 页面出现错误文案');
      if (errors.length) {
        console.log(`  ⚠ ${errors.length} 条错误：`);
        errors.slice(0, 5).forEach((e) => console.log('     ' + e));
      }
      console.log(`  判定: ${ok ? 'OK' : '需检查'}`);
      report.push({ page: p.name, ok, note: `${info.textLen} 字符 / ${errors.length} 错误` });
    } catch (e) {
      console.log(`  ✗ 异常: ${String(e).slice(0, 200)}`);
      report.push({ page: p.name, ok: false, note: String(e).slice(0, 120) });
    }
  }

  await browser.close();

  // ============================ 3. 汇总 ============================
  console.log('\n================ 走查汇总 ================');
  const okCount = report.filter((r) => r.ok).length;
  report.forEach((r) => {
    console.log(`  ${r.ok ? '✓' : '✗'} ${r.page.padEnd(12)} ${r.note}`);
  });
  console.log(`\n通过 ${okCount}/${report.length}`);
  console.log(`截图目录: ${OUT}`);
  process.exit(okCount === report.length ? 0 : 2);
})();
