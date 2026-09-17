import { useState, useEffect, useCallback, useRef, Suspense, lazy } from "react";
import FlipButton from "./FlipButton.jsx";
import { splitStatements, statementAtOffset } from "./sqlSplit.js";

const SqlEditor = lazy(() => import("./SqlEditor.jsx"));

const API = "/api";

const SAMPLE_QUERIES = [
  { label: "Select all", sql: "SELECT * FROM employees" },
  { label: "Filter dept", sql: "SELECT name, salary FROM employees WHERE department = 'Engineering'" },
  { label: "Order salary", sql: "SELECT name, department, salary FROM employees ORDER BY salary DESC" },
  { label: "Group dept", sql: "SELECT department, COUNT(*) FROM employees GROUP BY department" },
  { label: "Join", sql: "SELECT employees.name, departments.budget FROM employees JOIN departments ON employees.department = departments.name" },
  { label: "Create table", sql: "CREATE TABLE students (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL, gpa DOUBLE)" },
  { label: "Index", sql: "CREATE INDEX idx_salary ON employees (salary)" },
  { label: "Analyze", sql: "ANALYZE employees" },
];

const ANSI_KEYWORDS = new Set([
  "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "INSERT", "INTO", "VALUES",
  "CREATE", "TABLE", "DROP", "DELETE", "UPDATE", "SET", "ORDER", "BY", "ASC",
  "DESC", "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "ON",
  "GROUP", "HAVING", "DISTINCT", "AS", "NULL", "IS", "IN", "LIKE", "BETWEEN",
  "BEGIN", "COMMIT", "ROLLBACK", "INDEX", "EXISTS", "UNION", "ALL", "TRUE", "FALSE",
  "PRIMARY", "KEY", "REFERENCES", "FOREIGN", "CASCADE", "CONSTRAINT", "UNIQUE",
  "CHECK", "DEFAULT", "ALTER", "ADD", "COLUMN", "VARCHAR", "INTEGER", "DOUBLE",
  "BOOLEAN", "TEXT", "BIGINT", "SMALLINT", "FLOAT", "REAL", "CHAR",
]);

function escHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function highlightSql(code) {
  if (!code) return "";
  const tokens = [];
  let i = 0;
  while (i < code.length) {
    if (code[i] === '-' && code[i + 1] === '-') {
      let end = code.indexOf('\n', i); if (end === -1) end = code.length;
      tokens.push({ type: 'comment', value: code.slice(i, end) }); i = end; continue;
    }
    if (code[i] === "'") {
      let j = i + 1; while (j < code.length && code[j] !== "'") { if (code[j] === '\\') j++; j++; }
      tokens.push({ type: 'string', value: code.slice(i, j + 1) }); i = j + 1; continue;
    }
    if (/\d/.test(code[i]) && (i === 0 || /[\s,=(><+\-*/]/.test(code[i - 1]))) {
      let j = i; while (j < code.length && /[\d.]/.test(code[j])) j++;
      tokens.push({ type: 'number', value: code.slice(i, j) }); i = j; continue;
    }
    if (/[A-Za-z_]/.test(code[i])) {
      let j = i; while (j < code.length && /[A-Za-z0-9_.]/.test(code[j])) j++;
      const word = code.slice(i, j);
      tokens.push({ type: ANSI_KEYWORDS.has(word.toUpperCase()) ? 'keyword' : 'identifier', value: word });
      i = j; continue;
    }
    tokens.push({ type: 'other', value: code[i] }); i++;
  }
  const cls = {
    keyword: 'tok-keyword',
    identifier: 'tok-ident',
    number: 'tok-number',
    string: 'tok-string',
    operator: 'tok-op',
    comment: 'tok-comment',
  };
  return tokens.map(t => {
    if (t.type === 'other') {
      if ('!=<>*,()'.includes(t.value)) return `<span class="tok-op">${escHtml(t.value)}</span>`;
      return escHtml(t.value);
    }
    return `<span class="${cls[t.type]}">${escHtml(t.value)}</span>`;
  }).join('');
}

function tokenColor(type) {
  if (type === "KEYWORD") return "var(--tok-keyword)";
  if (type === "STRING") return "var(--tok-string)";
  if (type === "NUMBER") return "var(--tok-number)";
  if (type === "COMMENT") return "var(--tok-comment)";
  return "var(--ink-2)";
}

/* Operation color encodes meaning: teal healthy, red destructive, maroon structural */
function opColor(op) {
  if (op === "INDEX_SCAN" || op === "INSERT" || op === "CREATE" || op === "HASH_JOIN") return "var(--teal)";
  if (op === "DROP" || op === "DELETE") return "var(--red)";
  if (op === "UPDATE" || op === "CHECKPOINT" || op === "NESTED_LOOP_JOIN") return "var(--maroon)";
  return "var(--line-strong)";
}

function Logo({ size = 22 }) {
  return (
    <span style={{
      width: size, height: size, flexShrink: 0,
      border: "2px solid var(--teal)",
      borderRadius: 3,
      display: "inline-flex", alignItems: "center", justifyContent: "center",
      fontFamily: "'IBM Plex Mono', monospace",
      fontSize: 8, fontWeight: 600, color: "var(--ink)",
      background: "var(--bg-inset)",
      letterSpacing: 0,
    }}>SQL</span>
  );
}

// ── SchemaPanel ──────────────────────────────────────────
function SchemaPanel({ schema, indexedKeys, onTableClick, onQuerySelect, history }) {
  const [expanded, setExpanded] = useState({});
  const [section, setSection] = useState("schema");

  const TABS = [
    { key: "schema", label: "Schema" },
    { key: "saved", label: "Saved" },
    { key: "hist", label: "History" },
  ];

  const savedQueries = [
    { name: "Top earners", sql: "SELECT * FROM employees ORDER BY salary DESC LIMIT 3", starred: true },
    { name: "Dept summary", sql: "SELECT department, COUNT(*), AVG(salary) FROM employees GROUP BY department", starred: false },
    { name: "Products price", sql: "SELECT * FROM products ORDER BY price DESC", starred: false },
  ];

  return (
    <div style={{ height: "100%", display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <div style={{ display: "flex", borderBottom: "1px solid var(--line)", flexShrink: 0 }}>
        {TABS.map(({ key, label }) => (
          <button key={key} onClick={() => setSection(key)} style={{
            flex: 1, padding: "10px 4px",
            fontSize: 13, fontWeight: section === key ? 600 : 400,
            background: "transparent", border: "none",
            borderBottom: section === key ? "2px solid var(--teal)" : "2px solid transparent",
            color: section === key ? "var(--teal-ink)" : "var(--ink-3)",
            cursor: "pointer",
          }}>{label}</button>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: "auto", padding: "6px 0" }}>
        {section === "schema" && schema.map((table) => (
          <div key={table.name}>
            <div
              onClick={() => setExpanded(e => ({ ...e, [table.name]: !e[table.name] }))}
              onKeyDown={e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setExpanded(x => ({ ...x, [table.name]: !x[table.name] })); } }}
              tabIndex={0} role="button" aria-expanded={!!expanded[table.name]}
              style={{
                display: "flex", alignItems: "center", gap: 8,
                padding: "8px 12px", cursor: "pointer",
                background: expanded[table.name] ? "var(--teal-wash)" : "transparent",
                borderLeft: expanded[table.name] ? "2px solid var(--teal)" : "2px solid transparent",
              }}
            >
              <span style={{ fontSize: 12, color: "var(--ink-3)", width: 12, flexShrink: 0 }}>
                {expanded[table.name] ? "–" : "+"}
              </span>
              <span className="data-text" style={{ flex: 1, fontSize: 13, fontWeight: 500, color: "var(--ink)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {table.name}
              </span>
              <span className="num" style={{ fontSize: 11, color: "var(--ink-3)" }}>{table.rowCount}</span>
            </div>
            {expanded[table.name] && (
              <div style={{ paddingBottom: 4, borderBottom: "1px solid var(--line)" }}>
                {table.columns.map(col => {
                  const isIndexed = indexedKeys?.has(`${table.name}.${col.name}`);
                  return (
                    <div key={col.name}
                      onClick={() => onTableClick(`SELECT * FROM ${table.name}`)}
                      style={{ display: "flex", alignItems: "center", gap: 8, padding: "5px 12px 5px 32px", cursor: "pointer", fontSize: 13 }}
                    >
                      <span style={{
                        width: 6, height: 6, flexShrink: 0, borderRadius: 1,
                        background: col.primaryKey ? "var(--gold)" : isIndexed ? "var(--teal)" : "var(--line-strong)",
                        border: col.primaryKey ? "1px solid var(--gold-line)" : "1px solid transparent",
                      }} />
                      <span className="data-text" style={{ flex: 1, color: "var(--ink-2)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{col.name}</span>
                      {col.primaryKey && <span style={{ fontSize: 11, color: "var(--ink-3)", border: "1px solid var(--gold-line)", background: "var(--gold-wash)", borderRadius: 3, padding: "0 4px" }}>PK</span>}
                      {isIndexed && !col.primaryKey && <span style={{ fontSize: 11, color: "var(--teal-ink)", border: "1px solid var(--teal)", borderRadius: 3, padding: "0 4px" }}>IDX</span>}
                      {col.notNull && !col.primaryKey && <span style={{ fontSize: 11, color: "var(--ink-2)", border: "1px solid var(--line-strong)", borderRadius: 3, padding: "0 4px" }}>NN</span>}
                      <span className="data-text" style={{ fontSize: 11, color: "var(--ink-3)" }}>{col.type}</span>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        ))}

        {section === "saved" && (
          <div style={{ padding: 10, display: "flex", flexDirection: "column", gap: 8 }}>
            {savedQueries.map((q, i) => (
              <div key={i} onClick={() => onQuerySelect(q.sql)}
                onKeyDown={e => { if (e.key === "Enter") onQuerySelect(q.sql); }}
                tabIndex={0} role="button"
                style={{
                  background: "var(--bg-inset)",
                  border: "1px solid var(--line)",
                  borderRadius: 8, padding: "10px 12px", cursor: "pointer",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 4 }}>
                  <span style={{ color: q.starred ? "var(--gold-line)" : "var(--ink-faint)", fontSize: 13 }}>{q.starred ? "★" : "☆"}</span>
                  <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>{q.name}</span>
                </div>
                <div className="data-text" style={{ fontSize: 11, color: "var(--ink-3)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{q.sql}</div>
              </div>
            ))}
          </div>
        )}

        {section === "hist" && (
          <div style={{ padding: "0 12px" }}>
            {(history || []).length === 0 ? (
              <div style={{ fontSize: 13, color: "var(--ink-3)", padding: "20px 4px", lineHeight: 1.6 }}>
                No history yet. Run a query and it will appear here.
              </div>
            ) : (history || []).slice(0, 8).map((entry, i) => (
              <div key={i} onClick={() => onQuerySelect(entry.sql)}
                style={{ padding: "8px 0", borderBottom: "1px solid var(--line)", cursor: "pointer" }}
              >
                <div className="data-text" style={{ fontSize: 12, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", marginBottom: 4 }}>{entry.sql}</div>
                <div style={{ display: "flex", gap: 12, fontSize: 11, alignItems: "baseline" }}>
                  <span style={{ color: entry.success ? "var(--teal-ink)" : "var(--red)", fontWeight: 600 }}>
                    {entry.success ? "ok" : "error"}
                  </span>
                  {entry.rowCount > 0 && <span className="num" style={{ color: "var(--ink-2)" }}>{entry.rowCount} rows</span>}
                  <span className="num" style={{ color: "var(--ink-3)" }}>{entry.elapsedMs} ms</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ── ResultGrid ───────────────────────────────────────────
function ResultGrid({ columns, rows }) {
  if (!columns || columns.length === 0) return null;
  return (
    <div style={{ overflowX: "auto", border: "1px solid var(--line)", borderRadius: 6 }}>
      <table className="grid-table">
        <thead>
          <tr>
            <th className="n" style={{ width: 44 }}>Row</th>
            {columns.map(col => (
              <th key={col} className="data-text">{col}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i}>
              <td className="n" style={{ color: "var(--ink-3)" }}>{i + 1}</td>
              {columns.map(col => {
                const val = row[col];
                let color = "var(--ink)";
                if (val === null || val === undefined) color = "var(--ink-3)";
                else if (val === true) color = "var(--teal-ink)";
                else if (val === false) color = "var(--red)";
                return (
                  <td key={col} style={{ color, whiteSpace: "nowrap" }}>
                    {val === null || val === undefined
                      ? <span style={{ fontStyle: "italic" }}>null</span>
                      : String(val)}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ── PlanNodeDisplay ──────────────────────────────────────
function PlanNodeDisplay({ node, depth = 0 }) {
  const [open, setOpen] = useState(true);
  const [showStats, setShowStats] = useState(false);
  if (!node) return null;
  const color = opColor(node.operation);
  const isJoin = node.operation === "HASH_JOIN" || node.operation === "NESTED_LOOP_JOIN";
  const strategy = node.stats?.strategy;
  const statEntries = node.stats ? Object.entries(node.stats) : [];
  return (
    <div className={depth > 0 ? "plan-indent" : ""}>
      <div onClick={() => node.children?.length && setOpen(o => !o)}
        onKeyDown={e => { if ((e.key === "Enter" || e.key === " ") && node.children?.length) { e.preventDefault(); setOpen(o => !o); } }}
        tabIndex={node.children?.length ? 0 : undefined} role={node.children?.length ? "button" : undefined}
        aria-expanded={node.children?.length ? open : undefined}
        className="expandable"
        style={{
          display: "flex", alignItems: "baseline", gap: 8,
          padding: "7px 10px", borderRadius: 4, marginBottom: 4,
          fontSize: 13,
          background: "var(--bg-inset)",
          border: "1px solid var(--line)",
          borderLeft: `3px solid ${color}`,
          cursor: node.children?.length ? "pointer" : "default",
        }}
      >
        <span className="data-text" style={{ fontSize: 12, fontWeight: 600, color: "var(--ink)" }}>{node.operation}</span>
        {isJoin && (
          <span style={{ fontSize: 11, color: "var(--teal-ink)", border: "1px solid var(--teal)", borderRadius: 3, padding: "0 4px", whiteSpace: "nowrap" }}>
            {strategy === "hash_join" ? "hash" : strategy === "nested_loop" ? "nested loop" : node.operation}
          </span>
        )}
        <span style={{ fontSize: 13, color: "var(--ink-2)", flex: 1 }}>{node.description}</span>
        {statEntries.length > 0 && (
          <FlipButton front={showStats ? "hide stats" : "stats"} tone="neutral" size="xs"
            onClick={e => { e.stopPropagation(); setShowStats(s => !s); }}
            aria-expanded={showStats} aria-label={showStats ? "Hide node statistics" : "Show node statistics"} />
        )}
        {node.stats?.cost !== undefined && (
          <span className="num" style={{ fontSize: 11, color: "var(--ink-3)" }}>{node.stats.cost}</span>
        )}
        {node.children?.length > 0 && (
          <span style={{ fontSize: 12, color: "var(--ink-3)" }}>{open ? "–" : "+"}</span>
        )}
      </div>
      {showStats && statEntries.length > 0 && (
        <div style={{ margin: "0 0 6px 0", padding: "8px 10px", background: "var(--bg-raised)", border: "1px solid var(--line)", borderRadius: 4 }}>
          {statEntries.map(([k, v]) => (
            <div key={k} style={{ display: "flex", justifyContent: "space-between", gap: 12, padding: "2px 0", fontSize: 12 }}>
              <span style={{ color: "var(--ink-3)" }}>{k.replace(/_/g, " ")}</span>
              <span className="data-text" style={{ color: "var(--ink)", textAlign: "right", overflow: "hidden", textOverflow: "ellipsis" }}>
                {k === "build_table" ? `build: ${v}` : k === "probe_table" ? `probe: ${v}` : String(v)}
              </span>
            </div>
          ))}
        </div>
      )}
      {open && node.children?.map((child, i) => (
        <PlanNodeDisplay key={i} node={child} depth={depth + 1} />
      ))}
    </div>
  );
}

// ── Flamegraph ───────────────────────────────────────────
function Flamegraph({ elapsedMs, compact }) {
  const total = Math.max(1, Number(elapsedMs) || 0);
  const segs = [
    { label: "parse", pct: 12, bg: "var(--bg-wash)", fg: "var(--ink-2)" },
    { label: "plan", pct: 15, bg: "var(--gold-wash)", fg: "var(--ink)" },
    { label: "execute", pct: 73, bg: "var(--teal-wash)", fg: "var(--teal-ink)" },
  ];
  return (
    <div>
      <div style={{ width: "100%", height: compact ? 14 : 18, display: "flex", borderRadius: 3, overflow: "hidden", margin: "8px 0", border: "1px solid var(--line-strong)" }}>
        {segs.map(s => (
          <div key={s.label} className="flame-seg" style={{
            width: `${s.pct}%`, background: s.bg,
            display: "flex", alignItems: "center", justifyContent: "center",
            borderRight: "1px solid var(--line)",
          }}>
            {!compact && <span className="num" style={{ fontSize: 11, color: s.fg }}>{(total * s.pct / 100).toFixed(0)} ms</span>}
          </div>
        ))}
      </div>
      <div style={{ display: "flex", gap: 16, fontSize: 11, color: "var(--ink-3)" }}>
        {segs.map(s => (
          <span key={s.label} style={{ display: "flex", gap: 6, alignItems: "baseline" }}>
            <span style={{ width: 8, height: 8, background: s.bg, border: "1px solid var(--line-strong)", display: "inline-block" }} />
            <span>{s.label}</span>
            <span className="num">{(total * s.pct / 100).toFixed(0)} ms</span>
          </span>
        ))}
      </div>
    </div>
  );
}

// ── AutoChart ────────────────────────────────────────────
function AutoChart({ columns, rows }) {
  if (!columns || !rows || rows.length === 0) return null;
  const numCol = columns.find(c => rows.some(r => typeof r[c] === "number" || (!isNaN(Number(r[c])) && r[c] !== "" && r[c] !== null)));
  const labelCol = columns.find(c => typeof rows[0][c] === "string");
  if (!numCol) return null;
  const vals = rows.slice(0, 8).map(r => ({
    label: labelCol ? String(r[labelCol]).slice(0, 10) : "",
    val: Math.max(0, Number(r[numCol]) || 0),
  }));
  const maxVal = Math.max(...vals.map(v => v.val), 1);
  const maxIdx = vals.findIndex(v => v.val === maxVal);
  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 6, height: 72, padding: "4px 0", borderBottom: "1px solid var(--line-strong)" }}>
        {vals.map((v, i) => {
          const pct = Math.max(8, (v.val / maxVal) * 100);
          const highlight = i === maxIdx && vals.length > 1;
          return (
            <div key={i} title={`${v.label}, ${v.val}`} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "stretch", justifyContent: "flex-end", height: "100%" }}>
              <div style={{
                height: `${pct}%`, borderRadius: "3px 3px 0 0",
                background: highlight ? "var(--gold)" : "var(--teal)",
                border: highlight ? "1px solid var(--gold-line)" : "1px solid var(--teal)",
                borderBottom: "none",
              }} />
            </div>
          );
        })}
      </div>
      <div style={{ display: "flex", gap: 6, marginTop: 4 }}>
        {vals.map((v, i) => (
          <div key={i} className="data-text" style={{ flex: 1, textAlign: "center", fontSize: 11, color: "var(--ink-3)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {v.label || (i + 1)}
          </div>
        ))}
      </div>
      <div className="data-text" style={{ fontSize: 11, color: "var(--ink-3)", marginTop: 6 }}>Column: {numCol}</div>
    </div>
  );
}

// ── TokenDisplay ─────────────────────────────────────────
function TokenDisplay({ tokens }) {
  if (!tokens || !tokens.length) return null;
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, padding: "6px 0" }}>
      {tokens.map((t, i) => (
        <span key={i} className="data-text" style={{
          background: "var(--bg-inset)", borderRadius: 4, padding: "3px 8px",
          fontSize: 12, color: tokenColor(t.type),
          border: "1px solid var(--line)",
          fontWeight: t.type === "KEYWORD" ? 600 : 400,
        }}>{t.value || t.type}</span>
      ))}
    </div>
  );
}

// ── WalDisplay ───────────────────────────────────────────
function WalDisplay({ walLog }) {
  if (!walLog || walLog.length === 0) return (
    <div style={{ color: "var(--ink-3)", fontSize: 13, padding: "20px 0" }}>
      No WAL entries yet. Run an INSERT, UPDATE, or DELETE.
    </div>
  );
  const opStyle = (op) => {
    if (op === "INSERT") return { color: "var(--teal-ink)", bg: "var(--teal-wash)", border: "var(--teal)" };
    if (op === "DELETE") return { color: "var(--red)", bg: "var(--red-wash)", border: "var(--red)" };
    return { color: "var(--maroon)", bg: "var(--bg-wash)", border: "var(--maroon)" };
  };
  return (
    <div style={{ overflowX: "auto", borderRadius: 6, border: "1px solid var(--line)" }}>
      <table className="grid-table">
        <thead>
          <tr><th className="n">Seq</th><th>Op</th><th>Table</th><th>Payload</th></tr>
        </thead>
        <tbody>
          {walLog.map((entry, i) => {
            const s = opStyle(entry.operation);
            return (
              <tr key={i}>
                <td className="n" style={{ color: "var(--ink-3)" }}>{entry.sequenceNumber}</td>
                <td><span style={{ fontSize: 11, fontWeight: 600, padding: "1px 8px", borderRadius: 4, background: s.bg, color: s.color, border: `1px solid ${s.border}` }}>{entry.operation}</span></td>
                <td className="data-text" style={{ color: "var(--ink)" }}>{entry.tableName}</td>
                <td className="data-text" style={{ color: "var(--ink-2)", maxWidth: 280, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontSize: 11 }}>{JSON.stringify(entry.payload)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ── HistoryPanel ─────────────────────────────────────────
function HistoryPanel({ token, onRerun }) {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    fetch(`${API}/history?limit=50`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json()).then(d => { setEntries(d.history || []); setLoading(false); })
      .catch(() => setLoading(false));
  }, [token]);

  if (loading) return <div style={{ color: "var(--ink-3)", fontSize: 13, padding: "20px 0" }}>Loading history.</div>;
  if (!entries.length) return <div style={{ color: "var(--ink-3)", fontSize: 13, padding: "20px 0" }}>No query history yet.</div>;

  return (
    <div style={{ display: "flex", flexDirection: "column" }}>
      {entries.map((e, i) => (
        <div key={i} onClick={() => onRerun(e.sql)}
          onKeyDown={ev => { if (ev.key === "Enter") onRerun(e.sql); }}
          tabIndex={0} role="button"
          style={{ padding: "10px 2px", borderBottom: "1px solid var(--line)", cursor: "pointer" }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", gap: 12, marginBottom: 4 }}>
            <code className="data-text" style={{ fontSize: 12, color: e.success ? "var(--ink)" : "var(--red)", whiteSpace: "pre-wrap", wordBreak: "break-all", flex: 1 }}>{e.sql}</code>
            <span className="num" style={{ fontSize: 11, color: "var(--ink-3)", flexShrink: 0 }}>{e.elapsedMs} ms</span>
          </div>
          <div style={{ display: "flex", gap: 12, fontSize: 11, color: "var(--ink-3)" }}>
            <span style={{ color: e.success ? "var(--teal-ink)" : "var(--red)", fontWeight: 600 }}>{e.success ? "ok" : "error"}</span>
            <span>{new Date(e.timestamp).toLocaleString()}</span>
            {e.rowCount > 0 && <span className="num">{e.rowCount} rows</span>}
            {e.message && <span>{e.message}</span>}
          </div>
        </div>
      ))}
    </div>
  );
}

function parseErrorLine(msg) {
  if (!msg) return null;
  const m = String(msg).match(/line\s+(\d+)/i);
  return m ? Number(m[1]) : null;
}

// ── App ───────────────────────────────────────────────────
export default function App() {
  const [theme, setTheme] = useState(() => localStorage.getItem("sql-theme") || "dark");
  useEffect(() => { document.documentElement.setAttribute("data-theme", theme); localStorage.setItem("sql-theme", theme); }, [theme]);
  const toggleTheme = () => setTheme(t => t === "dark" ? "light" : "dark");
  const isDark = theme === "dark";

  const [token, setToken] = useState(() => localStorage.getItem("sql-token") || "");
  const [username, setUsername] = useState(() => localStorage.getItem("sql-username") || "");
  const [authView, setAuthView] = useState("login");
  const [authForm, setAuthForm] = useState({ username: "", password: "" });
  const [authError, setAuthError] = useState("");
  const [authLoading, setAuthLoading] = useState(false);

  const [tabs, setTabs] = useState([{ id: 1, label: "query_1.sql", sql: "SELECT employees.name, employees.salary, departments.budget\nFROM employees\nJOIN departments\n  ON employees.department = departments.name\nWHERE employees.salary > 80000" }]);
  const [activeTabId, setActiveTabId] = useState(1);
  const [nextTabId, setNextTabId] = useState(2);
  const activeTab = tabs.find(t => t.id === activeTabId) || tabs[0];

  const addTab = () => {
    const id = nextTabId;
    setTabs(prev => [...prev, { id, label: `query_${id}.sql`, sql: "" }]);
    setActiveTabId(id); setNextTabId(n => n + 1);
  };

  const closeTab = (id, e) => {
    e.stopPropagation();
    if (tabs.length === 1) {
      const nid = nextTabId;
      setTabs([{ id: nid, label: `query_${nid}.sql`, sql: "" }]);
      setActiveTabId(nid); setNextTabId(n => n + 1); setResult(null); return;
    }
    const rest = tabs.filter(t => t.id !== id);
    setTabs(rest);
    if (activeTabId === id) setActiveTabId(rest[rest.length - 1].id);
  };

  const updateTabSql = (sql) => setTabs(prev => prev.map(t => t.id === activeTabId ? { ...t, sql } : t));

  const [result, setResult] = useState(null);
  const [schema, setSchema] = useState([]);
  const [loading, setLoading] = useState(false);
  const [resultTab, setResultTab] = useState("results");
  const [backendDown, setBackendDown] = useState(false);
  const [walLog, setWalLog] = useState([]);
  const [indexedKeys, setIndexedKeys] = useState(new Set());
  const [sessionId] = useState(() => "sess_" + Math.random().toString(36).substring(2, 10));
  const [txnActive, setTxnActive] = useState(false);
  const [txnId, setTxnId] = useState(0);
  const [sidebarHistory, setSidebarHistory] = useState([]);
  const [queryCount, setQueryCount] = useState(0);
  const [planPinned, setPlanPinned] = useState(false);

  const getHeaders = useCallback(() => ({
    "Content-Type": "application/json",
    "X-Session-Id": sessionId,
    Authorization: `Bearer ${token}`,
  }), [sessionId, token]);

  const loadSchema = useCallback(async () => {
    try {
      const r = await fetch(`${API}/schema`, { headers: { Authorization: `Bearer ${token}` } });
      const d = await r.json(); setSchema(d.tables || []); setBackendDown(false);
    } catch { setBackendDown(true); }
  }, [token]);

  const loadIndexes = useCallback(async () => {
    try {
      const r = await fetch(`${API}/indexes`, { headers: { Authorization: `Bearer ${token}` } });
      const d = await r.json(); setIndexedKeys(new Set(d.indexes || []));
    } catch { }
  }, [token]);

  const loadWal = useCallback(async () => {
    try {
      const r = await fetch(`${API}/wal`, { headers: { Authorization: `Bearer ${token}` } });
      const d = await r.json(); setWalLog(d || []);
    } catch { }
  }, [token]);

  const loadSidebarHistory = useCallback(async () => {
    try {
      const r = await fetch(`${API}/history?limit=20`, { headers: { Authorization: `Bearer ${token}` } });
      const d = await r.json(); setSidebarHistory(d.history || []);
    } catch { }
  }, [token]);

  useEffect(() => {
    if (token) { loadSchema(); loadIndexes(); loadSidebarHistory(); }
  }, [token, loadSchema, loadIndexes, loadSidebarHistory]);

  const doSignup = async () => {
    setAuthLoading(true); setAuthError("");
    try {
      const r = await fetch(`${API}/auth/signup`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(authForm) });
      const d = await r.json();
      if (d.success) { localStorage.setItem("sql-token", d.token); localStorage.setItem("sql-username", d.username); setToken(d.token); setUsername(d.username); }
      else setAuthError(d.error);
    } catch { setAuthError("Cannot reach server"); }
    setAuthLoading(false);
  };

  const doLogin = async () => {
    setAuthLoading(true); setAuthError("");
    try {
      const r = await fetch(`${API}/auth/login`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(authForm) });
      const d = await r.json();
      if (d.success) { localStorage.setItem("sql-token", d.token); localStorage.setItem("sql-username", d.username); setToken(d.token); setUsername(d.username); }
      else setAuthError(d.error);
    } catch { setAuthError("Cannot reach server"); }
    setAuthLoading(false);
  };

  const doLogout = () => { localStorage.removeItem("sql-token"); localStorage.removeItem("sql-username"); setToken(""); setUsername(""); };

  const editorRef = useRef(null);
  const [runningMode, setRunningMode] = useState(null);

  const postStatement = async (sql) => {
    const r = await fetch(`${API}/query`, { method: "POST", headers: getHeaders(), body: JSON.stringify({ sql }) });
    return r.json();
  };

  const applyResultMeta = (d) => {
    setResult(d); setTxnActive(d.txnActive); setTxnId(d.txnId || 0); setQueryCount(q => q + 1);
  };

  const refreshAfterRun = async () => {
    await loadSchema(); await loadIndexes(); await loadWal(); await loadSidebarHistory();
  };

  const backendUnreachable = () => ({
    success: false, error: "Cannot reach backend. Is it running on :8081?", columns: [], rows: [], tokens: [],
  });

  // Run Line: execute only the statement under the cursor (Ctrl+Enter).
  const runLine = async () => {
    if (loading) return;
    const full = activeTab.sql;
    if (!full.trim()) return;
    let target = full.trim();
    const editor = editorRef.current;
    if (editor) {
      const model = editor.getModel();
      const pos = editor.getPosition();
      if (model && pos) {
        const stmt = statementAtOffset(splitStatements(full), model.getOffsetAt(pos));
        if (!stmt) return;
        target = stmt.sql;
      }
    }
    setLoading(true); setRunningMode("line");
    try {
      const d = await postStatement(target);
      applyResultMeta(d);
      if (d.success) { await refreshAfterRun(); setResultTab("results"); }
    } catch {
      setResult(backendUnreachable());
    }
    setLoading(false); setRunningMode(null);
  };

  // Run Full Script: execute every statement top to bottom (Ctrl+Shift+Enter).
  // Stops at the first error; the main panel shows the last statement's
  // result with a run summary, while History already holds each step.
  const runScript = async () => {
    if (loading) return;
    const stmts = splitStatements(activeTab.sql);
    if (!stmts.length) return;
    setLoading(true); setRunningMode("script");
    const total = stmts.length;
    let ok = 0, failedIndex = null, last = null;
    try {
      for (let i = 0; i < total; i++) {
        const d = await postStatement(stmts[i].sql);
        setTxnActive(d.txnActive); setTxnId(d.txnId || 0); setQueryCount(q => q + 1);
        last = d;
        if (!d.success) { failedIndex = i; break; }
        ok++;
      }
    } catch (e) {
      failedIndex = ok;
      last = { ...backendUnreachable(), error: String(e && e.message ? e.message : e) };
    }
    if (last) {
      const summary = failedIndex === null
        ? `${total}/${total} statements OK`
        : `${ok}/${total} — failed at statement ${failedIndex + 1}: ${last.error || "unknown error"}`;
      setResult({ ...last, message: summary });
      setResultTab("results");
    }
    await refreshAfterRun();
    setLoading(false); setRunningMode(null);
  };

  const runTransactionCmd = async (cmd) => {
    setLoading(true);
    try {
      const r = await fetch(`${API}/query`, { method: "POST", headers: getHeaders(), body: JSON.stringify({ sql: cmd }) });
      const d = await r.json(); setResult(d); setTxnActive(d.txnActive); setTxnId(d.txnId || 0); setQueryCount(q => q + 1); setResultTab("results");
    } catch (e) { setResult({ success: false, error: String(e), columns: [], rows: [], tokens: [] }); }
    setLoading(false);
  };

  const resetSchema = async () => {
    await fetch(`${API}/schema/reset`, { method: "POST", headers: { Authorization: `Bearer ${token}` } });
    await loadSchema(); await loadIndexes(); setResult(null);
  };

  const [notice, setNotice] = useState("");
  const noticeTimer = useRef(null);
  const showNotice = (msg) => {
    setNotice(msg);
    if (noticeTimer.current) clearTimeout(noticeTimer.current);
    noticeTimer.current = setTimeout(() => setNotice(""), 3500);
  };

  const analyzeAll = async () => {
    try {
      const r = await fetch(`${API}/stats/analyze-all`, { method: "POST", headers: { Authorization: `Bearer ${token}` } });
      const d = await r.json();
      const n = d.stats ? Object.keys(d.stats).length : 0;
      showNotice(n > 0 ? `Statistics refreshed for ${n} ${n === 1 ? "table" : "tables"}.` : "Statistics refresh requested.");
    } catch {
      showNotice("Statistics refresh failed: cannot reach backend.");
    }
  };

  const exportResult = (format, res) => {
    let content, filename, mime;
    if (format === "CSV") {
      const h = res.columns.join(",");
      const rows = res.rows.map(r => res.columns.map(c => { const v = r[c]; return v === null || v === undefined ? "" : (typeof v === "string" ? `"${v.replace(/"/g, '""')}"` : v); }).join(","));
      content = [h, ...rows].join("\n"); filename = "result.csv"; mime = "text/csv";
    } else if (format === "JSON") {
      content = JSON.stringify(res.rows, null, 2); filename = "result.json"; mime = "application/json";
    } else {
      const cols = res.columns.join(", ");
      const vals = res.rows.map(r => `(${res.columns.map(c => { const v = r[c]; return v === null ? "NULL" : (typeof v === "string" ? `'${v}'` : v); }).join(", ")})`);
      content = `INSERT INTO result (${cols}) VALUES\n${vals.join(",\n")};`; filename = "result.sql"; mime = "text/plain";
    }
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([content], { type: mime })); a.download = filename; a.click();
  };

  // ── AUTH GATE ─────────────────────────────────────────
  if (!token) {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "100vh", background: "var(--bg-app)", color: "var(--ink)", padding: 16 }}>
        <div style={{ width: 400 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
            <Logo size={26} />
            <span className="fs-work">SQL Playground</span>
          </div>
          <p style={{ fontSize: 13, color: "var(--ink-2)", marginBottom: 20 }}>Custom Java query engine with lexer, parser, and executor.</p>

          <div className="tile" style={{ borderRadius: 10, padding: "24px 22px" }}>
            <div style={{ display: "flex", background: "var(--bg-inset)", borderRadius: 6, padding: 3, gap: 4, marginBottom: 20, border: "1px solid var(--line)" }}>
              {["login", "signup"].map(view => (
                <button key={view} onClick={() => { setAuthView(view); setAuthError(""); }}
                  style={{
                    flex: 1, padding: "8px 0", fontSize: 13, fontWeight: authView === view ? 600 : 400,
                    background: authView === view ? "var(--bg-raised)" : "transparent",
                    color: authView === view ? "var(--ink)" : "var(--ink-3)",
                    border: authView === view ? "1px solid var(--line-strong)" : "1px solid transparent",
                    borderRadius: 4, cursor: "pointer",
                  }}
                >{view === "login" ? "Log in" : "Sign up"}</button>
              ))}
            </div>

            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              {[
                { key: "username", label: "Username", type: "text", ph: "your_username" },
                { key: "password", label: "Password", type: "password", ph: "password" },
              ].map(({ key, label, type, ph }) => (
                <div key={key}>
                  <label style={{ display: "block", fontSize: 13, fontWeight: 500, color: "var(--ink-2)", marginBottom: 6 }}>{label}</label>
                  <input type={type} value={authForm[key]} placeholder={ph}
                    onChange={e => setAuthForm(f => ({ ...f, [key]: e.target.value }))}
                    onKeyDown={e => e.key === "Enter" && (authView === "login" ? doLogin() : doSignup())}
                    style={{ width: "100%", padding: "10px 12px", background: "var(--bg-inset)", color: "var(--ink)", fontSize: 13, border: "1px solid var(--line-strong)", borderRadius: 4, outline: "none", boxSizing: "border-box" }}
                  />
                </div>
              ))}

              {authError && (
                <div style={{ fontSize: 13, color: "var(--red)", padding: "10px 12px", borderRadius: 4, background: "var(--red-wash)", border: "1px solid var(--red)" }}>
                  {authError}
                </div>
              )}

              <FlipButton front={authLoading ? "Please wait" : authView === "login" ? "Log in" : "Create account"}
                tone="primary" size="md" onClick={authView === "login" ? doLogin : doSignup}
                disabled={authLoading} style={{ marginTop: 2, width: "100%" }} />
            </div>

            {authView === "signup" && (
              <div style={{ fontSize: 12, color: "var(--ink-3)", marginTop: 14, padding: "8px 10px", borderRadius: 4, background: "var(--bg-inset)", border: "1px solid var(--line)" }}>
                Use at least 3 characters, with letters, numbers, or underscores.
              </div>
            )}
          </div>

          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 14 }}>
            <span style={{ fontSize: 12, color: "var(--ink-3)" }}>Java lexer, parser, executor</span>
            <FlipButton front={isDark ? "Light mode" : "Dark mode"} tone="neutral" size="sm"
              onClick={toggleTheme} aria-pressed={isDark} aria-label="Toggle color theme" />
          </div>
        </div>
      </div>
    );
  }

  const errorLine = result && !result.success ? parseErrorLine(result.error) : null;

  // ── MAIN PLAYGROUND ───────────────────────────────────
  return (
    <div style={{ background: "var(--bg-app)", height: "100vh", display: "flex", flexDirection: "column", color: "var(--ink)", overflow: "hidden" }}>

      {/* Header */}
      <div className="panel-rule" style={{ background: "var(--bg-raised)", padding: "0 14px", height: 48, display: "flex", alignItems: "center", gap: 12, flexShrink: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Logo size={24} />
          <span style={{ fontSize: 15, fontWeight: 600 }}>SQL Playground</span>
          <span style={{ fontSize: 11, padding: "2px 8px", borderRadius: 4, background: "var(--bg-inset)", color: "var(--ink-2)", border: "1px solid var(--line)" }}>Java engine</span>
        </div>

        <div style={{ flex: 1 }} />

        {backendDown && (
          <span style={{ fontSize: 12, color: "var(--red)", background: "var(--red-wash)", border: "1px solid var(--red)", borderRadius: 4, padding: "4px 10px" }}>
            Backend offline
          </span>
        )}
        {loading && <span className="run-live" style={{ fontSize: 12, color: "var(--teal-ink)", fontWeight: 600 }}>Running</span>}

        <button onClick={toggleTheme} aria-pressed={isDark} aria-label="Toggle color theme" title="Toggle theme"
          style={{ width: 30, height: 30, display: "inline-flex", alignItems: "center", justifyContent: "center", background: "transparent", border: "1px solid var(--line)", borderRadius: 4, color: "var(--ink-2)", cursor: "pointer", fontSize: 15 }}>
          {isDark ? "☀" : "☾"}
        </button>

        <FlipButton front="Reset data" tone="danger" size="sm" onClick={resetSchema} />

        <FlipButton front="Analyze all" tone="primary" size="sm" onClick={analyzeAll} />

        <div style={{ display: "flex", alignItems: "center", gap: 8, background: "var(--bg-inset)", border: "1px solid var(--line)", borderRadius: 12, padding: "3px 10px 3px 3px" }}>
          <div style={{ width: 24, height: 24, borderRadius: "50%", background: "var(--bg-wash)", border: "1px solid var(--line-strong)", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 10, fontWeight: 600, color: "var(--ink)" }}>{username.slice(0, 2).toUpperCase()}</div>
          <span style={{ fontSize: 13, fontWeight: 500 }}>{username}</span>
          <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--teal)" }} />
        </div>

        <FlipButton front="Log out" tone="neutral" size="sm" onClick={doLogout} />
      </div>

      {notice && (
        <div role="status" style={{ background: "var(--teal-wash)", borderBottom: "1px solid var(--teal)", color: "var(--teal-ink)", fontSize: 13, padding: "7px 14px", flexShrink: 0 }}>
          {notice}
        </div>
      )}

      {/* Workspace bento */}
      <div className="workspace" style={{ flex: 1, overflow: "hidden" }}>

        {/* Left rail */}
        <div className="tile tile-schema">
          <SchemaPanel schema={schema} indexedKeys={indexedKeys} onTableClick={q => updateTabSql(q)} onQuerySelect={q => updateTabSql(q)} history={sidebarHistory} />
        </div>

        {/* Center column */}
        <div style={{ display: "flex", flexDirection: "column", gap: 12, minHeight: 0, minWidth: 0 }}>
          <div style={{ display: "flex", gap: 1, background: "var(--bg-raised)", border: "1px solid var(--line)", borderRadius: "6px 6px 0 0", borderBottom: "none", padding: "0 8px", overflowX: "auto", flexShrink: 0 }}>
            {tabs.map(tab => (
              <div key={tab.id} onClick={() => setActiveTabId(tab.id)}
                style={{
                  padding: "9px 12px", fontSize: 13, cursor: "pointer",
                  borderBottom: activeTabId === tab.id ? "2px solid var(--teal)" : "2px solid transparent",
                  color: activeTabId === tab.id ? "var(--ink)" : "var(--ink-3)",
                  whiteSpace: "nowrap", display: "flex", alignItems: "center", gap: 8,
                  background: activeTabId === tab.id ? "var(--teal-wash)" : "transparent",
                  fontWeight: activeTabId === tab.id ? 600 : 400,
                }}
              >
                <span style={{ width: 6, height: 6, borderRadius: 1, background: activeTabId === tab.id ? "var(--teal)" : "var(--line-strong)", flexShrink: 0 }} />
                <span className="data-text">{tab.label}</span>
                <button onClick={(e) => closeTab(tab.id, e)} aria-label={`Close ${tab.label}`}
                  style={{ color: "var(--ink-3)", fontSize: 14, width: 20, height: 20, display: "flex", alignItems: "center", justifyContent: "center", borderRadius: 3, border: "none", background: "transparent", cursor: "pointer", lineHeight: 1 }}>×</button>
              </div>
            ))}
            <button onClick={addTab} aria-label="New query tab" style={{ padding: "6px 10px", fontSize: 16, color: "var(--ink-3)", cursor: "pointer", background: "transparent", border: "none", lineHeight: 1 }}>+</button>
          </div>

          <div style={{ display: "flex", gap: 6, padding: "8px 12px", background: "var(--bg-raised)", border: "1px solid var(--line)", borderTop: "none", borderBottom: "1px solid var(--line)", overflowX: "auto", flexShrink: 0, alignItems: "center" }}>
            <span style={{ fontSize: 13, color: "var(--ink-3)", whiteSpace: "nowrap", marginRight: 4 }}>Examples</span>
            {SAMPLE_QUERIES.map(q => (
              <FlipButton key={q.label} front={q.label} tone="neutral" size="xs" onClick={() => updateTabSql(q.sql)} aria-label={`Load example: ${q.label}`} style={{ flexShrink: 0 }} />
            ))}
          </div>

          {/* Editor tile */}
          <div className={`tile tile-editor ${loading ? "is-running" : ""}`} style={{ flex: "7 1 0", padding: 12 }}>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 2px 10px" }}>
              <span className="panel-title">SQL editor</span>
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <span style={{ fontSize: 12, color: "var(--ink-3)", display: "flex", alignItems: "center", gap: 4 }}>
                  <kbd className="k">Ctrl</kbd>+<kbd className="k">↵</kbd> line
                </span>
                <span style={{ fontSize: 12, color: "var(--ink-3)", display: "flex", alignItems: "center", gap: 4 }}>
                  <kbd className="k">Ctrl</kbd>+<kbd className="k">Shift</kbd>+<kbd className="k">↵</kbd> script
                </span>
                <FlipButton front={runningMode === "line" ? "Running" : "Run Line"} back="Ctrl+↵" tone="primary" size="md"
                  onClick={runLine} disabled={loading} className={runningMode === "line" ? "run-live" : ""} aria-label="Run statement at cursor" />
                <FlipButton front={runningMode === "script" ? "Running" : "Run Script"} back="Ctrl+Shift+↵" tone="neutral" size="md"
                  onClick={runScript} disabled={loading} className={runningMode === "script" ? "run-live" : ""} aria-label="Run full script" />
              </div>
            </div>
            <div style={{ flex: 1, overflow: "auto" }}>
              <Suspense fallback={<div style={{ border: "1px solid var(--line)", borderRadius: 3, background: "var(--bg-inset)", padding: 12, fontSize: 13, color: "var(--ink-3)" }}>Loading editor.</div>}>
                <SqlEditor value={activeTab.sql} onChange={updateTabSql} onRun={runLine} onRunScript={runScript} editorRef={editorRef} schema={schema} history={sidebarHistory} theme={theme} errorLine={errorLine} errorMessage={result && !result.success ? result.error : ""} />
              </Suspense>
            </div>
            {result && !result.success && result.error && (
              <div role="alert" style={{ marginTop: 10, background: "var(--red-wash)", border: "1px solid var(--red)", borderLeft: "3px solid var(--red)", borderRadius: 4, padding: "10px 12px", color: "var(--red)", fontSize: 13, lineHeight: 1.6 }}>
                {result.error}
              </div>
            )}
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 10, paddingTop: 10, borderTop: "1px solid var(--line)" }}>
              <span style={{ width: 7, height: 7, borderRadius: "50%", background: txnActive ? "var(--teal)" : "var(--line-strong)" }} />
              <span style={{ fontSize: 13, fontWeight: 500, color: txnActive ? "var(--teal-ink)" : "var(--ink-3)" }}>{txnActive ? `TXN #${txnId} active` : "Auto-commit"}</span>
              {[
                { label: "Begin", cmd: "BEGIN", enabled: !txnActive, tone: "neutral" },
                { label: "Commit", cmd: "COMMIT", enabled: txnActive, tone: "primary" },
                { label: "Rollback", cmd: "ROLLBACK", enabled: txnActive, tone: "danger" },
              ].map(({ label, cmd, enabled, tone }) => (
                <FlipButton key={label} front={label} tone={tone} size="sm"
                  onClick={() => enabled && runTransactionCmd(cmd)} disabled={!enabled} />
              ))}
              <div style={{ flex: 1 }} />
              <span className="data-text" style={{ fontSize: 11, color: "var(--ink-3)" }}>{sessionId}</span>
            </div>
          </div>

          {/* Output tile */}
          <div className="tile tile-output" style={{ flex: "4 1 0" }}>
            <div style={{ display: "flex", borderBottom: "1px solid var(--line)", padding: "0 12px", flexShrink: 0, alignItems: "center", overflowX: "auto" }}>
              {["results", "plan", "chart", "tokens", "wal", "history"].map(tab => (
                <button key={tab} onClick={() => setResultTab(tab)} style={{
                  padding: "10px 12px", fontSize: 13, fontWeight: resultTab === tab ? 600 : 400,
                  background: "transparent", border: "none",
                  borderBottom: resultTab === tab ? "2px solid var(--teal)" : "2px solid transparent",
                  color: resultTab === tab ? "var(--teal-ink)" : "var(--ink-3)",
                  cursor: "pointer", textTransform: "capitalize",
                }}>{tab}</button>
              ))}
              <div style={{ flex: 1 }} />
              {result && (
                <div style={{ display: "flex", alignItems: "center", gap: 10, paddingLeft: 12 }}>
                  {result.success && (
                    <span style={{ fontSize: 12, fontWeight: 600, color: "var(--teal-ink)" }}>
                      {result.columns.length > 0 ? `${result.rows.length} rows` : (result.message || "Done")}
                    </span>
                  )}
                  <span className="num" style={{ fontSize: 11, color: "var(--ink-3)" }}>{result.elapsedMs} ms</span>
                  {result.success && result.columns?.length > 0 && (
                    <div style={{ display: "flex", gap: 4 }}>
                      {["CSV", "JSON", "SQL"].map(fmt => (
                        <FlipButton key={fmt} front={fmt} tone="neutral" size="xs" onClick={() => exportResult(fmt, result)} aria-label={`Export results as ${fmt}`} />
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div style={{ flex: 1, overflow: "auto", padding: "12px 14px" }}>
              {!result && (
                <div style={{ color: "var(--ink-3)", marginTop: 24, fontSize: 13, lineHeight: 1.6 }}>
                  <div style={{ marginBottom: 6 }}>Run a query to see results.</div>
                  <div style={{ fontSize: 12, display: "flex", alignItems: "center", gap: 4 }}>
                    <kbd className="k">Ctrl</kbd>+<kbd className="k">↵</kbd> runs the line,
                    <kbd className="k">Ctrl</kbd>+<kbd className="k">Shift</kbd>+<kbd className="k">↵</kbd> runs the script.
                  </div>
                </div>
              )}
              {result && result.success && result.columns.length === 0 && resultTab === "results" && (
                <div style={{ color: "var(--teal-ink)", fontSize: 13, padding: "8px 0", display: "flex", alignItems: "center", gap: 8 }}>
                  <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--teal)" }} />
                  {result.message}
                </div>
              )}
              {result && result.success && result.columns.length > 0 && resultTab === "results" && (
                <div className="result-in"><ResultGrid columns={result.columns} rows={result.rows} /></div>
              )}
              {result && resultTab === "plan" && (
                <div>
                  <p style={{ fontSize: 13, color: "var(--ink-3)", marginBottom: 10 }}>Execution plan, with cost per node.</p>
                  {result.plan ? <PlanNodeDisplay node={result.plan} /> : <div style={{ color: "var(--ink-3)" }}>No plan available.</div>}
                </div>
              )}
              {result && resultTab === "chart" && (
                <div>
                  <p style={{ fontSize: 13, color: "var(--ink-3)", marginBottom: 10 }}>First numeric column, up to 8 rows. Gold marks the maximum.</p>
                  <AutoChart columns={result.columns} rows={result.rows} />
                </div>
              )}
              {result && resultTab === "tokens" && (
                <div>
                  <p style={{ fontSize: 13, color: "var(--ink-3)", marginBottom: 8 }}>Token stream from the Java lexer.</p>
                  <TokenDisplay tokens={result.tokens} />
                </div>
              )}
              {result && resultTab === "wal" && (
                <div>
                  <p style={{ fontSize: 13, color: "var(--ink-3)", marginBottom: 10 }}>Write-ahead log, recorded before execution.</p>
                  <WalDisplay walLog={walLog} />
                </div>
              )}
              {resultTab === "history" && (
                <HistoryPanel token={token} onRerun={sql => updateTabSql(sql)} />
              )}
            </div>

            <div style={{ borderTop: "1px solid var(--line)", background: "var(--bg-inset)", padding: "6px 14px", display: "flex", alignItems: "center", gap: 12, fontSize: 11, color: "var(--ink-3)", flexShrink: 0 }}>
              {result?.plan?.operation === "INDEX_SCAN" && <span style={{ color: "var(--teal-ink)", fontWeight: 600 }}>Index scan used</span>}
              {result?.success && <span>{result.columns.length > 0 ? `${result.rows.length} rows returned` : (result.message || "")}</span>}
              {result && <span className="num">{result.elapsedMs} ms</span>}
              <div style={{ flex: 1 }} />
              {queryCount > 0 && <span className="num">{queryCount} {queryCount === 1 ? "query" : "queries"} run</span>}
            </div>
          </div>

          {/* Pinned plan breakout */}
          {planPinned && result?.plan && (
            <div className="tile tile-plan-breakout" style={{ padding: 12, flexShrink: 0, maxHeight: 260, overflow: "auto" }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
                <span className="panel-title">Plan detail</span>
                <FlipButton front="Unpin" tone="neutral" size="xs" onClick={() => setPlanPinned(false)} />
              </div>
              <PlanNodeDisplay node={result.plan} />
              {result?.elapsedMs !== undefined && <div style={{ marginTop: 10 }}><Flamegraph elapsedMs={result.elapsedMs} /></div>}
            </div>
          )}
        </div>

        {/* Right rail */}
        <div className="tile tile-side">
          <div style={{ padding: "12px 14px", borderBottom: "1px solid var(--line)" }}>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
              <span className="panel-title">Execution plan</span>
              {result?.plan && <FlipButton front={planPinned ? "Unpin" : "Expand"} tone="neutral" size="xs" onClick={() => setPlanPinned(p => !p)} aria-label={planPinned ? "Unpin execution plan" : "Expand execution plan"} />}
            </div>
            {result?.plan
              ? <div style={{ marginBottom: 8 }}><PlanNodeDisplay node={result.plan} /></div>
              : <div style={{ fontSize: 13, color: "var(--ink-3)", lineHeight: 1.6 }}>Run a query to see the plan.</div>}
            {result?.elapsedMs !== undefined && (
              <div style={{ marginTop: 10, paddingTop: 10, borderTop: "1px solid var(--line)" }}>
                <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>Timing</div>
                <Flamegraph elapsedMs={result.elapsedMs} compact />
              </div>
            )}
          </div>

          <div style={{ padding: "12px 14px", borderBottom: "1px solid var(--line)" }}>
            <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>Chart</div>
            {result?.success && result?.columns?.length > 0 && result?.rows?.length > 0
              ? <AutoChart columns={result.columns} rows={result.rows} />
              : <div style={{ fontSize: 13, color: "var(--ink-3)", lineHeight: 1.6 }}>Run a SELECT to see a chart.</div>}
          </div>

          <div style={{ padding: "12px 14px" }}>
            <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>Session</div>
            <div style={{ background: "var(--bg-inset)", border: "1px solid var(--line)", borderRadius: 8, padding: "10px 12px" }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 6 }}>
                <span className="data-text" style={{ fontSize: 12, color: "var(--ink)" }}>{sessionId}</span>
                <span style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, color: "var(--teal-ink)" }}>
                  <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--teal)", display: "inline-block" }} />
                  active
                </span>
              </div>
              <div style={{ fontSize: 12, color: "var(--ink-2)", marginBottom: 2 }}>
                {txnActive ? `TXN #${txnId} in progress` : "Auto-commit mode"}
              </div>
              <div className="num" style={{ fontSize: 11, color: "var(--ink-3)", textAlign: "left" }}>
                {queryCount > 0 ? `${queryCount} ${queryCount === 1 ? "query" : "queries"} run` : "No queries yet"}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
