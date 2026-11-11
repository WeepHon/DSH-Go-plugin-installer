// DSH 插件安装器：用 DSH 自己的兼容性判定做预检（在 proot 运行时内执行）。
import { readFileSync } from 'node:fs';

const BOOT = '/opt/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js';
const [manifestPath, exemptionsPath] = process.argv.slice(2);
let out = { ok: false, runtime: null, compatible: null, issue: null, error: null };
try {
  const boot = await import(BOOT);
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  let exemptions = {};
  try { if (exemptionsPath) exemptions = JSON.parse(readFileSync(exemptionsPath, 'utf8')) || {}; } catch { exemptions = {}; }
  let runtime = null;
  try { runtime = boot.getDshRuntimeVersion(); } catch { runtime = null; }
  let issue = null;
  try {
    const r = boot.evaluatePluginCompatibility(manifest, exemptions);
    issue = (r === undefined || r === null) ? null : r;
  } catch (e) {
    out = { ok: false, runtime, compatible: null, issue: null, error: String((e && e.message) || e) };
    process.stdout.write(JSON.stringify(out));
    process.exit(0);
  }
  out = { ok: true, runtime, compatible: issue === null, issue, error: null };
} catch (e) {
  out.error = String((e && e.message) || e);
  out.ok = false;
}
process.stdout.write(JSON.stringify(out));
