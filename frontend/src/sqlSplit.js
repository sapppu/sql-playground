/* Split SQL script text into statements on semicolons that appear
 * outside string literals (' " `) and comments (-- and slash-star).
 * Returns [{ sql, start, end }] where sql is trimmed, start is the
 * offset of its first non-whitespace char, and end is exclusive
 * (just past the terminating semicolon when present). Empty
 * statements from trailing semicolons or whitespace are skipped. */

export function splitStatements(text) {
  const out = [];
  const n = text.length;
  let segStart = 0; // offset where current segment's raw text begins
  let i = 0;

  const push = (termEnd) => {
    const raw = text.slice(segStart, termEnd);
    let sql = raw.trim();
    if (sql.endsWith(";")) sql = sql.slice(0, -1).trim();
    if (!sql) return;
    const first = segStart + raw.indexOf(sql[0]);
    out.push({ sql, start: first, end: termEnd });
  };

  while (i < n) {
    const c = text[i];
    const next = i + 1 < n ? text[i + 1] : "";
    if (c === "-" && next === "-") {
      const nl = text.indexOf("\n", i + 2);
      i = nl === -1 ? n : nl + 1;
      continue;
    }
    if (c === "/" && next === "*") {
      const close = text.indexOf("*/", i + 2);
      i = close === -1 ? n : close + 2;
      continue;
    }
    if (c === "'" || c === '"' || c === "`") {
      i += 1;
      while (i < n) {
        if (text[i] === c) {
          if (text[i + 1] === c) { i += 2; continue; } // doubled-quote escape
          i += 1;
          break;
        }
        if (text[i] === "\\" && i + 1 < n) { i += 2; continue; }
        i += 1;
      }
      continue;
    }
    if (c === ";") {
      push(i + 1);
      segStart = i + 1;
    }
    i += 1;
  }
  push(n);
  return out;
}

/* Pick the statement for a cursor offset: containing first, else the
 * nearest statement below the cursor, else the nearest above.
 * Returns null when there are no statements. */
export function statementAtOffset(stmts, offset) {
  if (!stmts.length) return null;
  for (const s of stmts) {
    if (s.start <= offset && offset < s.end) return s;
  }
  for (const s of stmts) {
    if (s.start > offset) return s;
  }
  return stmts[stmts.length - 1];
}
