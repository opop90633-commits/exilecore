// 2026-11-02 照站長：網頁規劃器（src/main/resources/web/planner.html）天賦樹配點規則的測試——
// 把 <tree-rules> 那一段拿出來跑（虛實交織的跳點、退點的連通性、跳點接出去的路徑、不見之途）。
// 樹跟插件的 EntwinedRulesTest 一樣，結果也要一樣。用法：node tools/planner_rules_test.mjs（在 repo 根目錄）
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const html = readFileSync(fileURLToPath(new URL("../src/main/resources/web/planner.html", import.meta.url)), "utf8");
const m = html.match(/\/\/ <tree-rules>[\s\S]*?\n(\s*const TreeRules = [\s\S]*?\n\s*\}\)\(\);)\s*\n\s*\/\/ <\/tree-rules>/);
assert.ok(m, "planner.html 裡找不到 <tree-rules> 那一段");
const TreeRules = new Function(m[1] + "\nreturn TreeRules;")();

// S－a－K（基石）－b－c－d－j2－j1－K2（基石）；j1 離 K 800（範圍內）、j2 離 K 1600（範圍外）；U 是觀星專屬，接在 a 旁邊
const raw = [
  ["S", "start", 0, 0, ["a"]], ["a", "small", 160, 0, ["K", "U"]], ["K", "keystone", 320, 0, ["b"]], ["b", "small", 480, 0, ["c"]],
  ["c", "small", 2400, 0, ["d"]], ["d", "small", 2400, 1600, ["j2"]], ["j2", "small", 320, 1600, ["j1"]], ["j1", "small", 320, 800, ["K2"]],
  ["K2", "keystone", 320, 1000, []], ["U", "notable", 200, 300, [], { stat: "asc.unseen_path", ascendancy: "starseer" }],
];
const order = raw.map(([id, type, px, py, links, unlock]) => ({ id, type, px, py, links, unlock }));
const byId = new Map(order.map(n => [n.id, n]));
const adj = new Map(order.map(n => [n.id, []]));
for (const n of order) for (const id of n.links) { adj.get(n.id).push(byId.get(id)); adj.get(id).push(n); }
const cfg = { unseenPath: "asc.unseen_path", entwined: "asc.entwined", entwinedRadius: 1150 };
const ctx = rules => ({ byId, neighbours: n => adj.get(n.id), rules: new Set(rules), cfg, hidden: () => false });
const E = ctx(["asc.entwined"]), NONE = ctx([]), UNSEEN = ctx(["asc.unseen_path"]);
const seeds = [byId.get("S")];
const ids = list => list.map(n => n.id);
const set = (...xs) => new Set(xs);
const n = id => byId.get(id);
let count = 0;
const test = (name, fn) => { fn(); count++; console.log("✔ " + name); };

test("跳點只在範圍內、基石不能跳", () => {
  const s = set("a", "K");
  assert.equal(TreeRules.canAllocate(n("j1"), s, seeds, E), true);
  assert.equal(TreeRules.canAllocate(n("j1"), s, seeds, NONE), false);
  assert.equal(TreeRules.canAllocate(n("j2"), s, seeds, E), false);
  assert.equal(TreeRules.canAllocate(n("K2"), s, seeds, E), false);
  assert.equal(TreeRules.canAllocate(n("j1"), set("a"), seeds, E), false, "要先配到基石");
  assert.deepEqual(ids(TreeRules.jumps(order, s, seeds, E)), ["j1"]);
  assert.deepEqual(ids(TreeRules.jumps(order, s, seeds, NONE)), []);
  assert.deepEqual(ids(TreeRules.anchors(order, s, seeds, E)), ["K"]);
});

test("路徑從跳點接過去", () => {
  const s = set("a", "K");
  assert.deepEqual(ids(TreeRules.pathTo(n("j2"), order, s, seeds, E)), ["j1", "j2"]);
  assert.deepEqual(ids(TreeRules.pathTo(n("j2"), order, s, seeds, NONE)), ["b", "c", "d", "j2"]);
  assert.deepEqual(ids(TreeRules.pathTo(n("j1"), order, s, seeds, E)), ["j1"]);
});

test("退點：跳點配的不靠連線（跟遊戲的 canRefund 一樣）", () => {
  const s = set("a", "K", "j1", "j2");
  assert.deepEqual([...TreeRules.reachable(s, seeds, E)].sort(), ["K", "a", "j1", "j2"]);
  assert.deepEqual([...TreeRules.reachable(s, seeds, NONE)].sort(), ["K", "a"]);
  assert.deepEqual([...TreeRules.removal(n("K"), s, seeds, E)].sort(), ["K", "j1", "j2"], "退基石，跳點配的跟著斷");
  assert.deepEqual([...TreeRules.removal(n("j1"), s, seeds, E)].sort(), ["j1", "j2"], "j2 只靠 j1 接著");
  assert.deepEqual([...TreeRules.removal(n("j2"), s, seeds, E)], ["j2"]);
  assert.deepEqual([...TreeRules.removal(n("a"), s, seeds, E)].sort(), ["K", "a", "j1", "j2"]);
});

test("不見之途", () => {
  assert.equal(TreeRules.unseen(n("U"), cfg), true);
  assert.equal(TreeRules.unseen(n("a"), cfg), false);
  assert.equal(TreeRules.canAllocate(n("U"), set("a"), seeds, E), false);
  assert.equal(TreeRules.canAllocate(n("U"), set("a"), seeds, UNSEEN), true);
  assert.deepEqual(ids(TreeRules.pathTo(n("U"), order, set(), seeds, NONE)), [], "被擋住的走不到");
  assert.deepEqual(ids(TreeRules.pathTo(n("U"), order, set(), seeds, UNSEEN)), ["a", "U"]);
  assert.deepEqual([...TreeRules.reachable(set("a", "U"), seeds, NONE)], ["a"], "退了不見之途：專屬的斷掉");
});

test("昇華天賦給的規則", () => {
  const asc = [{ id: "unseen_path", rules: ["asc.unseen_path"] }, { id: "entwined_realities" }, { id: "x", rules: [] }];
  assert.deepEqual([...TreeRules.rulesOf(asc, ["unseen_path", "entwined_realities"], { entwined_realities: ["asc.entwined"] })].sort(),
    ["asc.entwined", "asc.unseen_path"]);
  assert.deepEqual([...TreeRules.rulesOf(asc, ["x"], {})], []);
});

console.log(count + " tests successful");
