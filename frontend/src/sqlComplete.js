/* Incremental, local completion engine for the SQL playground.
 * Pure JS (no monaco import) so it can be unit-tested with node.
 * Monaco wrapper in App.jsx only maps these candidates to CompletionItems.
 */

export const SQL_KEYWORDS = [
  "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "INSERT", "INTO", "VALUES",
  "CREATE", "TABLE", "DROP", "DELETE", "UPDATE", "SET", "ORDER", "BY", "ASC",
  "DESC", "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "ON", "GROUP", "HAVING",
  "DISTINCT", "AS", "NULL", "IS", "IN", "LIKE", "BETWEEN", "BEGIN", "COMMIT",
  "ROLLBACK", "INDEX", "EXISTS", "UNION", "ALL", "TRUE", "FALSE",
  "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
  "ALTER", "ADD", "COLUMN",
];

export const SQL_FUNCTIONS = [
  "COUNT", "SUM", "AVG", "MAX", "MIN",
  "UPPER", "LOWER", "LENGTH", "ABS", "ROUND",
];

export const SQL_SNIPPETS = [
  { label: "SELECT * FROM", insert: "SELECT * FROM ${1:table}", detail: "Snippet" },
  { label: "CREATE TABLE", insert: "CREATE TABLE ${1:name} (${2:id} INTEGER PRIMARY KEY)", detail: "Snippet" },
  { label: "INSERT INTO", insert: "INSERT INTO ${1:table} (${2:columns}) VALUES (${3:values})", detail: "Snippet" },
];

const WORD_RE = /[A-Za-z_][A-Za-z0-9_]*/g;

/* Extract table names referenced via FROM / JOIN / UPDATE / INTO in full text. */
export function getReferencedTables(fullText, schema) {
  const known = new Set((schema || []).map(t => String(t.name).toLowerCase()));
  const refs = [];
  const re = /\b(?:FROM|JOIN|UPDATE|INTO)\s+([A-Za-z_][A-Za-z0-9_]*)/gi;
  let m;
  while ((m = re.exec(fullText || "")) !== null) {
    const name = m[1];
    if (!refs.some(r => r.toLowerCase() === name.toLowerCase())) refs.push(name);
  }
  // Keep schema-known tables first, preserve typed order.
  refs.sort((a, b) => {
    const ka = known.has(a.toLowerCase()) ? 0 : 1;
    const kb = known.has(b.toLowerCase()) ? 0 : 1;
    return ka - kb;
  });
  return refs;
}

/* Detect alias-dot context: "<ident>." immediately before cursor. */
export function getDotTarget(textBefore) {
  const m = (textBefore || "").match(/([A-Za-z_][A-Za-z0-9_]*)\.\s*([A-Za-z_][A-Za-z0-9_]*)?$/);
  return m ? m[1] : null;
}

/* Last meaningful keyword before cursor (uppercased), ignoring trailing word fragment. */
export function getLastKeyword(textBefore) {
  const clean = (textBefore || "").replace(/([A-Za-z_][A-Za-z0-9_]*)?$/, "");
  const words = clean.toUpperCase().match(/[A-Z_]+/g) || [];
  const keys = new Set([...SQL_KEYWORDS, "BY"]);
  for (let i = words.length - 1; i >= 0; i--) {
    if (keys.has(words[i])) return words[i];
  }
  return null;
}

export function getCompletionContext(textBefore, fullText, schema) {
  const dotTarget = getDotTarget(textBefore);
  if (dotTarget) return { kind: "dot-columns", dotTarget };
  const kw = getLastKeyword(textBefore);
  if (kw === "FROM" || kw === "JOIN" || kw === "INTO" || kw === "UPDATE" || kw === "TABLE")
    return { kind: "tables", keyword: kw };
  if (["SELECT", "WHERE", "BY", "GROUP", "HAVING", "ON", "SET", "AND", "OR", "NOT"].includes(kw))
    return { kind: "columns", keyword: kw };
  // Default: after comma in select list, or empty / fresh query -> columns+tables+keywords.
  // If no keyword yet, bias to tables+keywords (fresh "c" case still surfaces tables first).
  if (!kw) return { kind: "mixed", keyword: null };
  return { kind: "mixed", keyword: kw };
}

/* Recent identifiers from session history (most recent first, lowercased, deduped). */
export function getRecentIdentifiers(historyEntries, limit = 30) {
  const seen = new Set();
  const out = [];
  const entries = (historyEntries || []).slice(0, 20);
  for (const e of entries) {
    const sql = typeof e === "string" ? e : e.sql || "";
    const words = sql.match(WORD_RE) || [];
    for (const w of words) {
      const lw = w.toLowerCase();
      if (lw.length < 2) continue;
      if (SQL_KEYWORDS.includes(w.toUpperCase())) continue;
      if (SQL_FUNCTIONS.includes(w.toUpperCase())) continue;
      if (!seen.has(lw)) { seen.add(lw); out.push({ name: w, lower: lw }); }
      if (out.length >= limit) return out;
    }
  }
  return out;
}

function isPrefix(candLower, prefixLower) {
  return prefixLower === "" || candLower.startsWith(prefixLower);
}

function isFuzzy(candLower, prefixLower) {
  if (prefixLower === "") return true;
  // Ordered subsequence match (cheap fuzzy) + substring fallback.
  if (candLower.includes(prefixLower)) return true;
  let j = 0;
  for (let i = 0; i < candLower.length && j < prefixLower.length; i++) {
    if (candLower[i] === prefixLower[j]) j++;
  }
  return j === prefixLower.length;
}

/* Build memoized base candidates from live schema (call when schema changes, not per keystroke). */
export function buildBaseCandidates(schema) {
  const tables = (schema || []).map(t => ({
    type: "table",
    label: t.name,
    lower: String(t.name).toLowerCase(),
    detail: `Table (${(t.columns || []).length} cols)`,
  }));
  const tableSet = new Set(tables.map(t => t.lower));
  const colMap = new Map(); // lower -> {label, tables:Set, type}
  for (const t of (schema || [])) {
    for (const c of (t.columns || [])) {
      const lw = String(c.name).toLowerCase();
      if (!colMap.has(lw)) colMap.set(lw, { label: c.name, tables: new Set(), colType: c.type });
      colMap.get(lw).tables.add(t.name);
    }
  }
  const columns = [...colMap.entries()].map(([lower, v]) => ({
    type: "column",
    label: v.label,
    lower,
    detail: [...v.tables].slice(0, 3).join(", "),
    tables: [...v.tables],
    colType: v.colType,
  }));
  return { tables, columns, tableSet };
}

/**
 * Rank candidates for a prefix. Returns sorted array with sortText set.
 * Rules: exact prefix before fuzzy; schema-context before generic keywords;
 * shorter and recently-used before longer/unused. No plain alphabetical sort.
 */
export function rankCompletions({ prefix, context, base, recentList, fullText }) {
  const p = (prefix || "").toLowerCase();
  const recentIdx = new Map((recentList || []).map((r, i) => [r.lower, i]));
  const refs = getReferencedTables(fullText || "", null);
  const refSet = new Set(refs.map(r => r.toLowerCase()));
  const out = [];

  const push = (item) => out.push(item);

  // 1. Context schema matches
  if (context.kind === "dot-columns") {
    const target = context.dotTarget.toLowerCase();
    for (const c of base.columns) {
      if (!c.tables.some(t => t.toLowerCase() === target)) continue;
      const pre = isPrefix(c.lower, p);
      const fz = !pre && isFuzzy(c.lower, p);
      if (!pre && !fz) continue;
      const rec = recentIdx.has(c.lower) ? recentIdx.get(c.lower) : 99;
      push({
        ...c, match: pre ? "prefix" : "fuzzy",
        tier: pre ? 0 : 4,
        recent: rec,
        sortText: `${pre ? "0" : "4"}_${String(rec).padStart(2, "0")}_${String(c.label.length).padStart(3, "0")}_${c.lower}`,
      });
    }
    return out.sort((a, b) => (a.sortText < b.sortText ? -1 : 1));
  }

  const wantTables = context.kind === "tables" || context.kind === "mixed";
  const wantColumns = context.kind === "columns" || context.kind === "mixed";

  if (wantTables) {
    for (const t of base.tables) {
      const pre = isPrefix(t.lower, p);
      const fz = !pre && isFuzzy(t.lower, p);
      if (!pre && !fz) continue;
      // Edge: in column context handled below; here tables are primary.
      const rec = recentIdx.has(t.lower) ? recentIdx.get(t.lower) : 99;
      push({
        ...t, match: pre ? "prefix" : "fuzzy",
        tier: pre ? (rec < 99 ? 0 : 1) : 4,
        recent: rec,
        sortText: `${pre ? (rec < 99 ? "0" : "1") : "4"}_${String(rec).padStart(2, "0")}_${String(t.label.length).padStart(3, "0")}_${t.lower}`,
      });
    }
  }

  if (wantColumns) {
    for (const c of base.columns) {
      // Edge: do not re-suggest a referenced table name as a bare column candidate.
      if (base.tableSet.has(c.lower) && refSet.has(c.lower)) continue;
      const pre = isPrefix(c.lower, p);
      const fz = !pre && isFuzzy(c.lower, p);
      if (!pre && !fz) continue;
      const inRef = c.tables.some(t => refSet.has(t.toLowerCase()));
      const rec = recentIdx.has(c.lower) ? recentIdx.get(c.lower) : 99;
      // Columns from already-typed FROM tables rank above other columns.
      const tierBase = context.kind === "columns" ? 0 : 1;
      const tier = pre ? (inRef ? tierBase : tierBase + 1) : 4;
      push({
        ...c, match: pre ? "prefix" : "fuzzy",
        tier, recent: rec,
        sortText: `${tier}_${String(inRef ? 0 : 1)}_${String(rec).padStart(2, "0")}_${String(c.label.length).padStart(3, "0")}_${c.lower}`,
      });
    }
  }

  // 2. Keywords + functions (skip keyword already fully typed + followed by space is handled
  // by empty-prefix context: context ranking already puts tables/columns first).
  const textBefore = (thisTextBefore => thisTextBefore)(arguments?.[0]?.textBefore ?? "");
  const kwDone = textBefore;
  for (const kw of SQL_KEYWORDS) {
    const lw = kw.toLowerCase();
    const pre = isPrefix(lw, p);
    const fz = !pre && isFuzzy(lw, p);
    if (!pre && !fz) continue;
    if (p === "") continue; // empty prefix: keywords would drown context; snippets cover patterns
    // Edge: do not suggest a keyword identical to the just-typed word when cursor is right after a space.
    if (kwDone && new RegExp(`\\b${kw}\\s$`, "i").test(kwDone) && lw === p) continue;
    push({
      type: "keyword", label: kw, lower: lw, detail: "Keyword",
      match: pre ? "prefix" : "fuzzy", tier: pre ? 2 : 5, recent: 99,
      sortText: `${pre ? "2" : "5"}_1_99_${String(kw.length).padStart(3, "0")}_${lw}`,
    });
  }
  for (const fn of SQL_FUNCTIONS) {
    const lw = fn.toLowerCase();
    const pre = isPrefix(lw, p);
    const fz = !pre && isFuzzy(lw, p);
    if (!pre && !fz) continue;
    if (p === "") continue;
    push({
      type: "function", label: fn, lower: lw, detail: "Function",
      match: pre ? "prefix" : "fuzzy", tier: pre ? 2 : 5, recent: 99,
      sortText: `${pre ? "2" : "5"}_2_99_${String(fn.length).padStart(3, "0")}_${lw}`,
    });
  }

  // 3. Recent identifiers not already covered (boost: they surface even as fuzzy).
  // Gate by context so column recents do not pollute table slots and vice versa.
  const isTableCtx = context.kind === "tables";
  for (let i = 0; i < (recentList || []).length; i++) {
    const r = recentList[i];
    if (out.some(o => o.lower === r.lower)) continue;
    if (isTableCtx && !base.tableSet.has(r.lower)) continue;
    if (context.kind === "dot-columns") continue; // dot scope is strict
    const pre = isPrefix(r.lower, p);
    const fz = !pre && isFuzzy(r.lower, p);
    if (!pre && !fz) continue;
    if (p === "" && !pre) continue;
    push({
      type: "recent", label: r.name, lower: r.lower, detail: "Recent",
      match: pre ? "prefix" : "fuzzy", tier: pre ? 1 : 4, recent: i,
      sortText: `1_${String(i).padStart(2, "0")}_99_${r.lower}`,
    });
  }

  // 4. Snippets: prefix-only (never fuzzy) to avoid noise; plus empty-prefix patterns.
  for (const s of SQL_SNIPPETS) {
    const lw = s.label.toLowerCase();
    if (p === "") {
      push({
        type: "snippet", label: s.label, lower: lw, detail: s.detail, insert: s.insert,
        match: "prefix", tier: 3, recent: 99,
        sortText: `3_0_99_999_${lw}`,
      });
      continue;
    }
    if (!isPrefix(lw, p)) continue;
    push({
      type: "snippet", label: s.label, lower: lw, detail: s.detail, insert: s.insert,
      match: "prefix", tier: 3, recent: 99,
      sortText: `3_0_99_999_${lw}`,
    });
  }

  out.sort((a, b) => (a.sortText < b.sortText ? -1 : a.sortText > b.sortText ? 1 : 0));
  return out;
}
