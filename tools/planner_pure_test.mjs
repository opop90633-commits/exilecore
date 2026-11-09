// 2026-11-02 照站長：網頁規劃器（src/main/resources/web/planner.html）的復原堆疊、滑到節點的差異、存在瀏覽器的規劃、熱圖——
// 把 <planner-pure> 那一段拿出來跑。用法：node tools/planner_pure_test.mjs（在 repo 根目錄）
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const html = readFileSync(fileURLToPath(new URL("../src/main/resources/web/planner.html", import.meta.url)), "utf8");
const m = html.match(/\/\/ <planner-pure>[\s\S]*?\n(\s*const PlannerPure = [\s\S]*?\n\s*\}\)\(\);)\s*\n\s*\/\/ <\/planner-pure>/);
assert.ok(m, "planner.html 裡找不到 <planner-pure> 那一段");
const P = new Function(m[1] + "\nreturn PlannerPure;")();
let count = 0;
const test = (name, fn) => { fn(); count++; console.log("✔ " + name); };

test("復原、重做、新的一步把重做清掉", () => {
  const u = new P.UndoStack(100);
  assert.equal(u.canUndo(), false);
  u.reset("a");
  u.push("b");
  u.push("c");
  assert.equal(u.undo(), "b");
  assert.equal(u.undo(), "a");
  assert.equal(u.undo(), null, "最前面了");
  assert.equal(u.redo(), "b");
  u.push("d");
  assert.equal(u.canRedo(), false, "做了新的一步，重做的清掉");
  assert.deepEqual(u.list, ["a", "b", "d"]);
  u.replace("d2");
  assert.equal(u.current(), "d2", "不是玩家做的：改掉現在這一步");
  assert.equal(u.undoCount(), 2);
});

test("歷史最多 100 步", () => {
  const u = new P.UndoStack(100);
  u.reset(0);
  for (let i = 1; i <= 150; i++) u.push(i);
  assert.equal(u.list.length, 101);
  assert.equal(u.undoCount(), 100);
  let last = null;
  while (u.canUndo()) last = u.undo();
  assert.equal(last, 50, "最舊的丟掉");
  u.reset("x");
  assert.equal(u.canUndo(), false, "換職業清空");
});

test("LRU 丟最久沒用的", () => {
  const c = new P.Lru(2);
  c.set("a", 1); c.set("b", 2);
  assert.equal(c.get("a"), 1);
  c.set("c", 3);
  assert.equal(c.has("b"), false);
  assert.equal(c.has("a"), true);
  assert.equal(c.size, 2);
});

test("雜湊：一樣的字串一樣、不一樣的不一樣", () => {
  assert.equal(P.hashString("abc"), P.hashString("abc"));
  assert.notEqual(P.hashString("abc"), P.hashString("abd"));
});

test("存在瀏覽器的：版本、壞掉的忽略", () => {
  const hash = "EC1.3.witch.!AAE~W10!WzAsIiJd";
  assert.equal(P.unpackSave(P.packSave(hash, 1)), hash);
  assert.equal(P.unpackSave(P.packSave("#" + hash, 1)), hash, "前面的 # 拿掉");
  assert.equal(P.unpackSave(null), null);
  assert.equal(P.unpackSave(""), null);
  assert.equal(P.unpackSave("{壞掉"), null);
  assert.equal(P.unpackSave(JSON.stringify({ v: 99, hash })), null, "版本不認得");
  assert.equal(P.unpackSave(JSON.stringify({ v: P.SAVE_VERSION, hash: 5 })), null);
  assert.equal(P.unpackSave(JSON.stringify({ v: P.SAVE_VERSION, hash: "<script>" })), null, "不像規劃的");
  assert.equal(P.unpackSave(JSON.stringify({ v: P.SAVE_VERSION, hash: "" })), null);
});

test("差異只列有變的，技能照位置對", () => {
  const stats = [["life", "生命"], ["es", "能量護盾"], ["resist.fire", "火焰抗性", "%"]];
  const before = { n: { life: 1200, es: 300, "resist.fire": 67 }, s: [{ p: 0, name: "火球", d: 12345 }, { p: 1, name: "冰矛", d: 800 }] };
  const after = { n: { life: 1245, es: 300, "resist.fire": 75 }, s: [{ p: 0, name: "火球", d: 13120 }, { p: 1, name: "冰矛", d: 800 }] };
  const rows = P.diffRows(before, after, stats);
  assert.deepEqual(rows.map(r => r.key), ["life", "resist.fire", "dps:0"]);
  assert.equal(P.fmtDiff(rows[0]), "+45（1,200 → 1,245）");
  assert.equal(P.fmtDiff(rows[1]), "+8%（67% → 75%）");
  assert.equal(P.fmtDiff(rows[2]), "+6.3%（12,345 → 13,120）");
  const down = P.diffRows(after, before, stats);
  assert.equal(P.fmtDiff(down[0]), "−45（1,245 → 1,200）");
  assert.equal(down[2].diff < 0, true);
  assert.deepEqual(P.diffRows(before, before, stats), [], "沒變就沒有");
  assert.deepEqual(P.diffRows({ n: { life: 10 } }, { n: { life: 20 } }, stats).map(r => r.key), ["life"], "沒有技能：只有屬性");
  const fromZero = P.diffRows({ n: {}, s: [{ p: 2, name: "x", d: 0 }] }, { n: {}, s: [{ p: 2, name: "x", d: 50 }] }, stats);
  assert.equal(P.fmtDiff(fromZero[0]), "+50（0 → 50）", "原本 0 的寫數值");
});

test("熱圖的顏色：灰到紅、變少的另一種顏色", () => {
  assert.equal(P.heatColor(0, 10), "#55586e");
  assert.equal(P.heatColor(-1, 10), "#5b5f9a");
  assert.equal(P.heatColor(10, 10), "rgb(255,60,60)");
  assert.equal(P.heatColor(20, 10), "rgb(255,60,60)", "超過最大的當最大");
  assert.match(P.heatColor(2.5, 10), /^rgb\(235,220,90\)$/);
});

test("熱圖要算的節點：能配的往外幾步", () => {
  // 0（已配）－1－2－3－4；5 擋住
  const order = [0, 1, 2, 3, 4, 5].map(i => ({ id: "n" + i }));
  const links = { n0: ["n1"], n1: ["n0", "n2", "n5"], n2: ["n1", "n3"], n3: ["n2", "n4"], n4: ["n3"], n5: ["n1"] };
  const byId = new Map(order.map(n => [n.id, n]));
  const nb = n => links[n.id].map(id => byId.get(id));
  const allocated = new Set(["n0"]);
  const can = n => !allocated.has(n.id) && n.id !== "n5" && nb(n).some(x => allocated.has(x.id));
  const skip = n => allocated.has(n.id) || n.id === "n5";
  const pick = steps => P.heatCandidates(order, can, nb, skip, steps).map(c => c.node.id + ":" + c.steps);
  assert.deepEqual(pick(1), ["n1:1"]);
  assert.deepEqual(pick(3), ["n1:1", "n2:2", "n3:3"]);
  assert.deepEqual(pick(99), ["n1:1", "n2:2", "n3:3", "n4:4"], "擋住的不算");
});

// 2026-11-02 照站長（PoB2 的 EHP）：計算器的敵人設定
test("敵人設定：預設是 PoB2 的巔峰 Boss、自動等級、均衡；跟預設一樣不寫進連結", () => {
  assert.deepEqual(P.normEnemy(null), { tier: 2, level: 0, mix: "avg" });
  assert.equal(P.isDefaultEnemy({}), true);
  assert.equal(P.packEnemy(P.normEnemy(null)), null, "預設不寫");
  assert.deepEqual(P.unpackEnemy(undefined), { tier: 2, level: 0, mix: "avg" }, "舊連結沒有第 7 個");
  assert.deepEqual(P.unpackEnemy("壞掉"), { tier: 2, level: 0, mix: "avg" });
});

test("敵人設定：寫進連結再讀回來一樣", () => {
  for (const e of [{ tier: 0, level: 0, mix: "phys" }, { tier: 3, level: 90, mix: "chaos" }, { tier: 4, level: 0, mix: "avg" }, { tier: 1, level: 70, mix: [50, 20, 20, 10, 0] }]) {
    const packed = P.packEnemy(e);
    const text = JSON.stringify([0, "", [], [], [], "", packed]); // 計算器那一段的格式
    assert.deepEqual(P.unpackEnemy(JSON.parse(text)[6]), P.normEnemy(e));
  }
});

test("敵人設定：壞掉的照預設、比例跟預設一樣換成名字、等級夾住", () => {
  assert.deepEqual(P.normEnemy({ tier: 9, level: -5, mix: "什麼" }), { tier: 2, level: 0, mix: "avg" });
  assert.deepEqual(P.normEnemy({ tier: 1, level: 999, mix: [0, 0, 0, 0, 0] }), { tier: 1, level: 200, mix: "avg" }, "全 0 ＝均衡");
  assert.equal(P.normEnemy({ mix: [0, 30, 30, 30, 0] }).mix, "ele", "1：1：1 的元素＝全元素");
  assert.equal(P.normEnemy({ mix: [2, 0, 0, 0, 0] }).mix, "phys");
  assert.deepEqual(P.normEnemy({ mix: [1, 2, "x", -3, 4] }).mix, [1, 2, 0, 0, 4]);
});

test("敵人設定：送去伺服器的（均衡不送比例）", () => {
  assert.deepEqual(P.enemyJson(null), { tier: 2, level: 0 });
  assert.deepEqual(P.enemyJson({ tier: 0, level: 40, mix: "ele" }), { tier: 0, level: 40, mix: [0, 1, 1, 1, 0] });
  assert.deepEqual(P.enemyJson({ tier: 3, mix: [5, 0, 0, 0, 5] }), { tier: 3, level: 0, mix: [5, 0, 0, 0, 5] });
});

test("差異：EHP 也列", () => {
  const rows = P.diffRows({ n: { ehp: 10000 } }, { n: { ehp: 12500 } }, [["ehp", "EHP"]]);
  assert.equal(rows.length, 1);
  assert.equal(P.fmtDiff(rows[0]), "+2,500（10,000 → 12,500）");
});

// 2026-11-09 照站長「網頁天賦規劃器也補上兩組」：武器組天賦（跟遊戲的 WeaponSets 同一套）
test("武器組天賦：用掉的點＝一般＋兩組多的那組", () => {
  const allocated = new Set(["a", "b", "w1", "w2"]);
  assert.equal(P.wsUsed(allocated, new Set(["w1", "w2"]), new Set(["x1", "x2", "x3"])), 2 + 3, "第 II 組比較多：照 3 算");
  assert.equal(P.wsUsed(allocated, new Set(["w1", "w2"]), new Set()), 4);
  assert.equal(P.wsUsed(new Set(["a"]), new Set(), new Set()), 1, "沒有武器組天賦＝配了幾點");
});

test("武器組天賦：換組（兩組都配的留著）", () => {
  const next = P.wsSwap(new Set(["a", "w1", "both"]), new Set(["w1", "both"]), new Set(["x1", "both"]));
  assert.deepEqual([...next].sort(), ["a", "both", "x1"]);
});

test("武器組天賦：分享連結（舊連結沒有＝第 I 組、沒有武器組天賦）", () => {
  assert.equal(P.wsPack(1, [], []), null, "沒有就不寫");
  assert.deepEqual(P.wsPack(2, [], []), [2, [], []]);
  assert.deepEqual(P.wsPack(1, [9, 3], [5]), [1, [3, 9], [5]]);
  assert.deepEqual(P.wsUnpack([2, [3, "x", -1, 9], [5]]), { active: 2, ords1: [3, 9], ords2: [5] });
  assert.equal(P.wsUnpack(undefined), null);
  assert.equal(P.wsUnpack(0), null, "舊連結的第 8 格沒有");
});

test("武器組天賦：每一組上限＝基本＋昇華（百兵宗匠）", () => {
  assert.equal(P.wsCap(24, { weapon_master: 100 }, ["weapon_master", "other"]), 124);
  assert.equal(P.wsCap(24, {}, []), 24);
  assert.equal(P.wsCap(undefined, undefined, undefined), 0);
});

console.log(count + " 個測試都通過");
