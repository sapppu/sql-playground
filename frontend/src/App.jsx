import { useState, useEffect, useRef, useCallback } from "react";

const API = "/api";

const SAMPLE_QUERIES = [
  { label: "Select all", sql: "SELECT * FROM employees" },
  { label: "Filter dept", sql: "SELECT name, salary FROM employees WHERE department = 'Engineering'" },
  { label: "Order salary", sql: "SELECT name, department, salary FROM employees ORDER BY salary DESC" },
  { label: "Group dept", sql: "SELECT department, COUNT(*) FROM employees GROUP BY department" },
  { label: "Join", sql: "SELECT employees.name, departments.budget FROM employees JOIN departments ON employees.department = departments.name" },
  { label: "Create table", sql: "CREATE TABLE students (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL, gpa DOUBLE)" },
  { label: "Index", sql: "CREATE INDEX idx_salary ON employees (salary)" },
];

const ANSI_KEYWORDS = new Set([
  "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "INSERT", "INTO", "VALUES",
  "CREATE", "TABLE", "DROP", "DELETE", "UPDATE", "SET", "ORDER", "BY", "ASC",
  "DESC", "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "ON",
  "GROUP", "HAVING", "DISTINCT", "AS", "NULL", "IS", "IN", "LIKE", "BETWEEN",
  "BEGIN", "COMMIT", "ROLLBACK", "INDEX", "EXISTS", "UNION", "ALL", "TRUE", "FALSE",
  "PRIMARY", "KEY", "REFERENCES", "FOREIGN", "CASCADE", "CONSTRAINT", "UNIQUE",
  "NOT", "CHECK", "DEFAULT", "ALTER", "ADD", "COLUMN", "VARCHAR", "INTEGER", "DOUBLE",
  "BOOLEAN", "TEXT", "BIGINT", "SMALLINT", "FLOAT", "REAL", "CHAR",
]);

function highlightSql(code) {
  if (!code) return "";
  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
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
    if ('!=<>*,()'.includes(code[i])) {
      let op = code[i];
      if ((code[i] === '<' || code[i] === '>' || code[i] === '!') && code[i + 1] === '=') { op += '='; }
      else if (code[i] === '<' && code[i + 1] === '>') { op += '>'; }
      tokens.push({ type: 'operator', value: op }); i += op.length; continue;
    }
    tokens.push({ type: 'other', value: code[i] }); i++;
  }
  const colorMap = {
    keyword: 'color:#c084fc;font-weight:700;text-shadow:0 0 8px rgba(192,132,252,0.4)',
    identifier: 'color:#e2e8f8',
    number: 'color:#fbbf24;text-shadow:0 0 6px rgba(251,191,36,0.3)',
    string: 'color:#34d399;text-shadow:0 0 6px rgba(52,211,153,0.3)',
    operator: 'color:#f472b6',
    comment: 'color:#4b587a;font-style:italic',
  };
  return tokens.map(t => {
    const style = colorMap[t.type];
    const safe = esc(t.value);
    return style ? `<span style="${style}">${safe}</span>` : safe;
  }).join('');
}

function highlightTokenType(type) {
  const map = {
    KEYWORD: "#c084fc", STRING: "#34d399", NUMBER: "#fbbf24",
    IDENTIFIER: "#e2e8f8", OPERATOR: "#f472b6",
    PUNCTUATION: "#94a3c8", COMMENT: "#4b587a",
  };
  return map[type] || "#94a3c8";
}

const OP_COLORS = {
  PROJECT: "#a78bfa", FILTER: "#fbbf24", SORT: "#34d399",
  LIMIT: "#f87171", HASH_AGG: "#818cf8", INDEX_SCAN: "#38bdf8",
  SEQ_SCAN: "#9ca3af", INNER_JOIN: "#f472b6", LEFT_JOIN: "#fb923c",
  INSERT: "#34d399", CREATE: "#34d399", DROP: "#f87171",
  DELETE: "#f87171", UPDATE: "#fbbf24", CHECKPOINT: "#9ca3af",
};

// ── Design tokens ────────────────────────────────────────
const C = {
  bg0: "var(--bg0)", bg1: "var(--bg1)", bg2: "var(--bg2)", bg3: "var(--bg3)", bg4: "var(--bg4)",
  accent: "var(--accent)", accent2: "var(--accent2)", accent3: "var(--accent3)", accent4: "var(--accent4)",
  txt: "var(--txt)", txt2: "var(--txt2)", txt3: "var(--txt3)", txt4: "var(--txt4)",
  grey: "var(--grey)", grey2: "var(--grey2)", grey3: "var(--grey3)",
  grn: "var(--grn)", grn2: "var(--grn2)",
  amb: "var(--amb)", amb2: "var(--amb2)",
  red: "var(--red)", red2: "var(--red2)",
  prp: "var(--prp)", prp2: "var(--prp2)",
  pnk: "var(--pnk)", pnk2: "var(--pnk2)",
  brd: "var(--brd)", brd2: "var(--brd2)", brd3: "var(--brd3)",
  blue: "var(--neon-blue)", green: "var(--neon-green)", amber: "var(--neon-amber)",
  pink: "var(--neon-pink)", orange: "var(--neon-orange)", teal: "var(--neon-teal)",
};

// ── Logo SVG ─────────────────────────────────────────────
function Logo({ size = 24 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none">
      <defs>
        <linearGradient id="logoGrad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#a78bfa" />
          <stop offset="50%" stopColor="#7c3aed" />
          <stop offset="100%" stopColor="#38bdf8" />
        </linearGradient>
      </defs>
      <polygon points="16,2 28,8 28,24 16,30 4,24 4,8"
        fill="url(#logoGrad)" opacity="0.95" />
      <polygon points="16,8 22,11.5 22,20.5 16,24 10,20.5 10,11.5"
        fill="rgba(255,255,255,0.08)" />
      <text x="16" y="20" textAnchor="middle" fontSize="9" fontWeight="800"
        fill="white" fontFamily="JetBrains Mono, monospace">SQL</text>
    </svg>
  );
}

// ── SchemaPanel ──────────────────────────────────────────
function SchemaPanel({ schema, indexedKeys, onTableClick, onQuerySelect, history }) {
  const [expanded, setExpanded] = useState({});
  const [section, setSection] = useState("schema");

  const TABS = [
    { key: "schema", label: "Schema", color: "#a78bfa", icon: "⬡" },
    { key: "saved", label: "Saved", color: "#f472b6", icon: "★" },
    { key: "hist", label: "History", color: "#34d399", icon: "⏱" },
  ];

  const savedQueries = [
    { name: "Top earners", sql: "SELECT * FROM employees ORDER BY salary DESC LIMIT 3", color: "#a78bfa", tags: ["analytics"] },
    { name: "Dept summary", sql: "SELECT department, COUNT(*), AVG(salary) FROM employees GROUP BY department", color: "#f472b6", tags: ["hr"] },
    { name: "Products price", sql: "SELECT * FROM products ORDER BY price DESC", color: "#34d399", tags: ["catalog"] },
  ];

  return (
    <div style={{ height: "100%", display: "flex", flexDirection: "column", background: C.bg1, overflow: "hidden" }}>

      {/* Section tabs */}
      <div style={{ display: "flex", borderBottom: `1px solid ${C.brd}`, flexShrink: 0, padding: "0 4px" }}>
        {TABS.map(({ key, label, color, icon }) => (
          <button key={key} onClick={() => setSection(key)} style={{
            flex: 1, padding: "10px 2px",
            fontSize: 10, fontWeight: 700, letterSpacing: ".05em",
            background: "transparent", border: "none",
            borderBottom: `2px solid ${section === key ? color : "transparent"}`,
            color: section === key ? color : C.txt3,
            cursor: "pointer", textTransform: "uppercase",
            transition: "color .15s, border-color .15s",
            display: "flex", alignItems: "center", justifyContent: "center", gap: 4,
          }}>
            <span style={{ fontSize: 11 }}>{icon}</span>
            {label}
          </button>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: "auto", padding: "6px 0" }}>

        {/* ── Schema ── */}
        {section === "schema" && schema.map((table, tableIdx) => {
          const tableColors = ["#a78bfa", "#38bdf8", "#34d399", "#f472b6", "#fbbf24", "#fb923c"];
          const tc = tableColors[tableIdx % tableColors.length];
          return (
            <div key={table.name} className="sidebar-anim">
              <div
                onClick={() => setExpanded(e => ({ ...e, [table.name]: !e[table.name] }))}
                className="schema-row"
                style={{
                  display: "flex", alignItems: "center", gap: 8,
                  padding: "8px 14px", cursor: "pointer",
                  background: expanded[table.name] ? `${tc}10` : "transparent",
                  borderLeft: `2px solid ${expanded[table.name] ? tc : "transparent"}`,
                  transition: "all .15s",
                }}
              >
                <div style={{
                  width: 8, height: 8, borderRadius: 2,
                  background: expanded[table.name] ? tc : C.grey,
                  flexShrink: 0, transition: "background .15s, box-shadow .15s",
                  boxShadow: expanded[table.name] ? `0 0 8px ${tc}` : "none",
                }} />
                <span style={{
                  flex: 1, fontSize: 12, fontWeight: 600,
                  color: expanded[table.name] ? C.txt : C.txt2,
                  letterSpacing: ".02em",
                }}>
                  {table.name}
                </span>
                <span style={{
                  fontSize: 9, color: "#fff", fontWeight: 700,
                  background: expanded[table.name] ? `${tc}40` : "rgba(107,114,128,0.2)",
                  borderRadius: 10, padding: "2px 7px",
                  border: `1px solid ${expanded[table.name] ? `${tc}60` : "transparent"}`,
                  transition: "all .15s",
                }}>{table.rowCount}r</span>
                <span style={{ fontSize: 9, color: C.txt3, marginLeft: 2 }}>
                  {expanded[table.name] ? "▲" : "▼"}
                </span>
              </div>
              {expanded[table.name] && (
                <div style={{ paddingLeft: 10, paddingBottom: 4 }}>
                  {table.columns.map(col => {
                    const isIndexed = indexedKeys?.has(`${table.name}.${col.name}`);
                    return (
                      <div key={col.name}
                        onClick={() => onTableClick(`SELECT * FROM ${table.name}`)}
                        style={{
                          display: "flex", alignItems: "center", gap: 6,
                          padding: "4px 14px", cursor: "pointer", fontSize: 11,
                          borderRadius: 4, transition: "background .12s",
                        }}
                        onMouseEnter={e => e.currentTarget.style.background = "rgba(255,255,255,0.04)"}
                        onMouseLeave={e => e.currentTarget.style.background = "transparent"}
                      >
                        <div style={{
                          width: 5, height: 5, borderRadius: "50%", flexShrink: 0,
                          background: col.primaryKey ? "#fbbf24"
                            : isIndexed ? "#38bdf8"
                              : C.grey,
                          boxShadow: col.primaryKey ? "0 0 6px rgba(251,191,36,0.5)"
                            : isIndexed ? "0 0 6px rgba(56,189,248,0.5)"
                              : "none",
                        }} />
                        <span style={{ flex: 1, color: C.txt2 }}>{col.name}</span>
                        {col.primaryKey && (
                          <span style={{
                            fontSize: 8, padding: "1px 5px", borderRadius: 3,
                            background: "rgba(251,191,36,0.12)", color: "#fbbf24",
                            border: "1px solid rgba(251,191,36,0.3)",
                            boxShadow: "0 0 6px rgba(251,191,36,0.2)",
                          }}>PK</span>
                        )}
                        {isIndexed && !col.primaryKey && (
                          <span className="badge-idx" style={{
                            fontSize: 8, padding: "1px 5px", borderRadius: 3,
                            background: "rgba(56,189,248,0.1)", color: "#38bdf8",
                            border: "1px solid rgba(56,189,248,0.25)",
                            boxShadow: "0 0 6px rgba(56,189,248,0.2)",
                          }}>IDX</span>
                        )}
                        <span style={{ fontSize: 9, color: C.txt3 }}>{col.type}</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}

        {/* ── Saved queries ── */}
        {section === "saved" && (
          <div style={{ padding: "8px" }}>
            {savedQueries.map((q, i) => (
              <div key={i} onClick={() => onQuerySelect(q.sql)}
                className="card-hover"
                style={{
                  background: `${q.color}0d`, border: `1px solid ${q.color}25`,
                  borderRadius: 8, padding: "10px 12px", marginBottom: 7,
                  cursor: "pointer", borderLeft: `3px solid ${q.color}`,
                  boxShadow: `0 2px 12px ${q.color}0a`,
                }}
                onMouseEnter={e => { e.currentTarget.style.background = `${q.color}18`; e.currentTarget.style.boxShadow = `0 4px 20px ${q.color}20`; }}
                onMouseLeave={e => { e.currentTarget.style.background = `${q.color}0d`; e.currentTarget.style.boxShadow = `0 2px 12px ${q.color}0a`; }}
              >
                <div style={{ fontSize: 12, color: q.color, fontWeight: 700, marginBottom: 5, letterSpacing: ".01em" }}>{q.name}</div>
                <div style={{ display: "flex", gap: 3 }}>
                  {q.tags.map(tag => (
                    <span key={tag} style={{
                      fontSize: 9, padding: "2px 7px", borderRadius: 10,
                      background: `${q.color}20`, color: q.color,
                      border: `1px solid ${q.color}35`, fontWeight: 600,
                    }}>{tag}</span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}

        {/* ── History ── */}
        {section === "hist" && (
          <div style={{ padding: "0 10px" }}>
            {(history || []).length === 0 ? (
              <div style={{
                fontSize: 12, color: C.txt3, padding: "20px 4px",
                lineHeight: 1.8, textAlign: "center",
              }}>
                <div style={{ fontSize: 24, marginBottom: 8, opacity: 0.4 }}>⏱</div>
                No history yet.
              </div>
            ) : (history || []).slice(0, 8).map((entry, i) => (
              <div key={i} onClick={() => onQuerySelect(entry.sql)}
                style={{
                  padding: "8px 0", borderBottom: `1px solid ${C.brd}`,
                  cursor: "pointer", animation: "fadeIn .15s ease",
                }}
                onMouseEnter={e => e.currentTarget.style.paddingLeft = "4px"}
                onMouseLeave={e => e.currentTarget.style.paddingLeft = "0"}
              >
                <div style={{
                  fontSize: 10, color: C.txt, whiteSpace: "nowrap",
                  overflow: "hidden", textOverflow: "ellipsis", marginBottom: 4,
                  fontFamily: "JetBrains Mono, monospace",
                }}>{entry.sql}</div>
                <div style={{ display: "flex", gap: 8, fontSize: 9 }}>
                  <span style={{
                    color: entry.success ? "#34d399" : "#f87171",
                    textShadow: entry.success ? "0 0 6px rgba(52,211,153,0.4)" : "0 0 6px rgba(248,113,113,0.4)",
                  }}>
                    {entry.success ? "✓ ok" : "✗ err"}
                  </span>
                  {entry.rowCount > 0 && <span style={{ color: "#a78bfa" }}>{entry.rowCount} rows</span>}
                  <span style={{ color: C.txt3 }}>{entry.elapsedMs}ms</span>
                </div>
              </div>
            ))}
          </div>
        )}

      </div>
    </div>
  );
}

// ── SqlEditor ────────────────────────────────────────────
function SqlEditor({ value, onChange, onRun, loading, schema }) {
  const textareaRef = useRef(null);
  const highlightRef = useRef(null);
  const [autocomplete, setAutocomplete] = useState(null);

  const allColumns = (schema || []).flatMap(t => (t.columns || []).map(c => ({ ...c, table: t.name })));

  const syncScroll = () => {
    if (highlightRef.current && textareaRef.current) {
      highlightRef.current.scrollTop = textareaRef.current.scrollTop;
      highlightRef.current.scrollLeft = textareaRef.current.scrollLeft;
    }
  };

  const handleChange = (val) => {
    onChange(val);
    const pos = (textareaRef.current?.selectionStart || val.length);
    const wordMatch = val.slice(0, pos).match(/([a-zA-Z_][a-zA-Z0-9_]*)$/);
    if (wordMatch) {
      const word = wordMatch[1].toLowerCase();
      const matches = allColumns.filter(c =>
        c.name.toLowerCase().startsWith(word) || c.table.toLowerCase().startsWith(word)
      ).slice(0, 8);
      if (matches.length > 0 && word.length >= 1) { setAutocomplete({ word, matches, pos }); return; }
    }
    setAutocomplete(null);
  };

  const acceptAutocomplete = (item) => {
    if (!autocomplete) return;
    const before = value.slice(0, autocomplete.pos - autocomplete.word.length);
    const after = value.slice(autocomplete.pos);
    onChange(before + item.name + after);
    setAutocomplete(null);
    setTimeout(() => {
      if (textareaRef.current) {
        const np = before.length + item.name.length;
        textareaRef.current.focus();
        textareaRef.current.selectionStart = np;
        textareaRef.current.selectionEnd = np;
      }
    }, 0);
  };

  const handleKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); onRun(); }
    if (e.key === "Tab") {
      e.preventDefault();
      const s = e.target.selectionStart, en = e.target.selectionEnd;
      onChange(value.substring(0, s) + "  " + value.substring(en));
      setTimeout(() => { e.target.selectionStart = e.target.selectionEnd = s + 2; }, 0);
    }
    if (e.key === "Escape") setAutocomplete(null);
  };

  const shared = {
    position: "absolute", top: 0, left: 0, right: 0, bottom: 0,
    padding: "14px 16px", margin: 0,
    fontFamily: "'JetBrains Mono','Fira Code','Courier New',monospace",
    fontSize: 13.5, lineHeight: 2.0,
    whiteSpace: "pre-wrap", wordWrap: "break-word",
    overflowWrap: "break-word", overflow: "auto",
    border: "none", outline: "none", background: "transparent",
  };

  return (
    <div style={{ position: "relative", minHeight: 100 }}>
      <div ref={highlightRef} aria-hidden="true"
        dangerouslySetInnerHTML={{
          __html: highlightSql(value)
            + '<span style="display:inline-block;width:2px;height:14px;background:#c084fc;vertical-align:middle;animation:blink 1s step-end infinite;box-shadow:0 0 8px rgba(192,132,252,0.8)"></span>'
        }}
        style={{ ...shared, color: C.txt2, pointerEvents: "none", userSelect: "none" }}
      />
      <textarea ref={textareaRef} value={value}
        onChange={e => handleChange(e.target.value)}
        onKeyDown={handleKeyDown}
        onScroll={syncScroll}
        spellCheck={false}
        style={{
          ...shared, color: "transparent",
          caretColor: "#c084fc", resize: "vertical", minHeight: 100,
        }}
      />
      {autocomplete && (
        <div style={{
          position: "absolute", left: 60, top: "100%", zIndex: 100,
          background: C.bg1, border: `1px solid rgba(192,132,252,0.35)`,
          borderRadius: 9, overflow: "hidden", width: 220, marginTop: 4,
          boxShadow: "0 12px 40px rgba(0,0,0,0.4), 0 0 0 1px rgba(192,132,252,0.1), 0 0 24px rgba(124,58,237,0.15)",
          animation: "slideUp .12s ease",
        }}>
          <div style={{
            padding: "5px 12px 3px", fontSize: 9, color: C.txt3,
            letterSpacing: ".08em", fontWeight: 700,
            borderBottom: `1px solid ${C.brd}`,
            background: "rgba(192,132,252,0.05)",
          }}>
            SUGGESTIONS
          </div>
          {autocomplete.matches.map((item, i) => (
            <div key={i} onClick={() => acceptAutocomplete(item)}
              className="autocomplete-item"
              style={{
                display: "flex", alignItems: "center", gap: 8,
                padding: "7px 12px", fontSize: 12, cursor: "pointer",
                background: i === 0 ? "rgba(192,132,252,0.08)" : "transparent",
              }}
              onMouseEnter={e => e.currentTarget.style.background = "rgba(192,132,252,0.12)"}
              onMouseLeave={e => e.currentTarget.style.background = i === 0 ? "rgba(192,132,252,0.08)" : "transparent"}
            >
              <span style={{
                fontSize: 9, color: "#38bdf8", background: "rgba(56,189,248,0.12)",
                border: "1px solid rgba(56,189,248,0.25)", borderRadius: 4, padding: "1px 5px",
                fontWeight: 700,
              }}>col</span>
              <span style={{ color: C.txt, flex: 1 }}>{item.name}</span>
              <span style={{ fontSize: 9, color: C.txt3, fontFamily: "monospace" }}>{item.type}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── ResultGrid ───────────────────────────────────────────
function ResultGrid({ columns, rows }) {
  if (!columns || columns.length === 0) return null;
  return (
    <div style={{
      overflowX: "auto", borderRadius: 10,
      border: `1px solid ${C.brd}`,
      boxShadow: "0 4px 24px rgba(0,0,0,0.2)",
    }}>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12.5 }}>
        <thead>
          <tr style={{ background: "rgba(124,58,237,0.08)" }}>
            <th style={{
              padding: "9px 14px", textAlign: "right", color: C.txt3,
              fontWeight: 700, borderBottom: `1px solid ${C.brd}`,
              fontSize: 10, letterSpacing: ".06em", width: 36,
            }}>#</th>
            {columns.map(col => (
              <th key={col} style={{
                padding: "9px 14px", textAlign: "left",
                color: "#c084fc", fontWeight: 700,
                borderBottom: `1px solid ${C.brd}`,
                fontSize: 10, letterSpacing: ".07em", whiteSpace: "nowrap",
                textShadow: "0 0 12px rgba(192,132,252,0.35)",
              }}>{col.toUpperCase()}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className="row-anim"
              style={{ transition: "background .12s" }}
              onMouseEnter={e => e.currentTarget.style.background = "rgba(124,58,237,0.06)"}
              onMouseLeave={e => e.currentTarget.style.background = "transparent"}
            >
              <td style={{
                padding: "7px 14px", color: C.txt4,
                borderBottom: `1px solid ${C.brd}`,
                textAlign: "right", fontSize: 10, fontFamily: "monospace",
              }}>{i + 1}</td>
              {columns.map(col => {
                const val = row[col];
                let color = C.txt;
                let shadow = "none";
                if (val === null || val === undefined) { color = C.txt3; }
                else if (typeof val === "boolean") {
                  color = val ? "#34d399" : "#f87171";
                  shadow = val ? "0 0 6px rgba(52,211,153,0.3)" : "0 0 6px rgba(248,113,113,0.3)";
                } else if (typeof val === "number") {
                  color = "#fbbf24"; shadow = "0 0 6px rgba(251,191,36,0.3)";
                } else if (val !== "" && !isNaN(Number(val))) {
                  color = "#fbbf24"; shadow = "0 0 6px rgba(251,191,36,0.3)";
                }
                return (
                  <td key={col} style={{
                    padding: "7px 14px", color,
                    whiteSpace: "nowrap",
                    borderBottom: `1px solid ${C.brd}`,
                    textShadow: shadow,
                    fontFamily: "JetBrains Mono, monospace",
                  }}>
                    {val === null || val === undefined
                      ? <span style={{ color: C.txt3, fontStyle: "italic", fontSize: 11 }}>null</span>
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
  if (!node) return null;
  const color = OP_COLORS[node.operation] || "#9ca3af";
  return (
    <>
      <div onClick={() => node.children?.length && setOpen(o => !o)}
        style={{
          display: "flex", alignItems: "center", gap: 7,
          padding: "7px 10px", borderRadius: 7, marginBottom: 4,
          fontSize: 12, marginLeft: depth * 16,
          background: `${color}10`,
          borderLeft: `3px solid ${color}`,
          cursor: node.children?.length ? "pointer" : "default",
          boxShadow: `inset 0 0 12px ${color}08`,
          transition: "all .15s",
        }}
        onMouseEnter={e => { e.currentTarget.style.background = `${color}1c`; e.currentTarget.style.boxShadow = `inset 0 0 16px ${color}12, 0 0 12px ${color}10`; }}
        onMouseLeave={e => { e.currentTarget.style.background = `${color}10`; e.currentTarget.style.boxShadow = `inset 0 0 12px ${color}08`; }}
      >
        <span style={{
          fontSize: 9, fontWeight: 800, padding: "2px 7px",
          borderRadius: 4, letterSpacing: ".08em",
          background: `${color}22`, color,
          border: `1px solid ${color}40`,
          boxShadow: `0 0 8px ${color}30`,
          textShadow: `0 0 8px ${color}60`,
        }}>{node.operation}</span>
        <span style={{ fontSize: 11, color: C.txt2, flex: 1 }}>{node.description}</span>
        {node.stats?.cost !== undefined && (
          <span style={{ fontSize: 9, color: C.txt3 }}>cost {node.stats.cost}</span>
        )}
        {node.children?.length > 0 && (
          <span style={{ fontSize: 9, color: C.txt3 }}>{open ? "▲" : "▼"}</span>
        )}
      </div>
      {open && node.children?.map((child, i) => (
        <PlanNodeDisplay key={i} node={child} depth={depth + 1} />
      ))}
    </>
  );
}

// ── Flamegraph ───────────────────────────────────────────
function Flamegraph({ elapsedMs }) {
  const segs = [
    { label: "parse", pct: 12, color: "#a78bfa", glow: "rgba(167,139,250,0.5)" },
    { label: "plan", pct: 15, color: "#fbbf24", glow: "rgba(251,191,36,0.5)" },
    { label: "execute", pct: 73, color: "#34d399", glow: "rgba(52,211,153,0.5)" },
  ];
  return (
    <>
      <div style={{
        width: "100%", height: 20, display: "flex",
        borderRadius: 6, overflow: "hidden",
        margin: "8px 0", border: `1px solid ${C.brd}`,
      }}>
        {segs.map(s => (
          <div key={s.label} className="flame-bar" style={{
            width: `${s.pct}%`, background: s.color,
            display: "flex", alignItems: "center", justifyContent: "center",
            fontSize: 9, color: "#000", fontWeight: 800,
            letterSpacing: ".03em",
          }}>
            {(elapsedMs * s.pct / 100).toFixed(0)}ms
          </div>
        ))}
      </div>
      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 9, color: C.txt3 }}>
        <span style={{ color: "#a78bfa", textShadow: "0 0 6px rgba(167,139,250,0.4)" }}>● parse</span>
        <span style={{ color: "#fbbf24", textShadow: "0 0 6px rgba(251,191,36,0.4)" }}>● plan</span>
        <span style={{ color: "#34d399", textShadow: "0 0 6px rgba(52,211,153,0.4)" }}>● exec</span>
      </div>
    </>
  );
}

// ── AutoChart ────────────────────────────────────────────
function AutoChart({ columns, rows }) {
  if (!columns || !rows || rows.length === 0) return null;
  const numCol = columns.find(c => rows.some(r => typeof r[c] === "number" || (!isNaN(Number(r[c])) && r[c] !== "" && r[c] !== null)));
  const labelCol = columns.find(c => typeof rows[0][c] === "string");
  if (!numCol) return null;
  const vals = rows.slice(0, 8).map(r => ({
    label: labelCol ? String(r[labelCol]).slice(0, 8) : "",
    val: Math.max(0, Number(r[numCol]) || 0),
  }));
  const maxVal = Math.max(...vals.map(v => v.val), 1);
  const BAR_COLORS = [
    { bar: "#818cf8", glow: "rgba(129,140,248,0.5)" },
    { bar: "#f472b6", glow: "rgba(244,114,182,0.5)" },
    { bar: "#34d399", glow: "rgba(52,211,153,0.5)" },
    { bar: "#fbbf24", glow: "rgba(251,191,36,0.5)" },
    { bar: "#fb923c", glow: "rgba(251,146,60,0.5)" },
    { bar: "#38bdf8", glow: "rgba(56,189,248,0.5)" },
    { bar: "#a78bfa", glow: "rgba(167,139,250,0.5)" },
    { bar: "#f87171", glow: "rgba(248,113,113,0.5)" },
  ];
  return (
    <>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 5, height: 60, padding: "4px 0" }}>
        {vals.map((v, i) => {
          const pct = Math.max(8, (v.val / maxVal) * 100);
          const { bar, glow } = BAR_COLORS[i % BAR_COLORS.length];
          return (
            <div key={i} title={`${v.label}: ${v.val}`}
              className="chart-bar"
              style={{
                flex: 1, borderRadius: "4px 4px 0 0",
                height: `${pct}%`,
                background: bar,
                opacity: 0.85,
                boxShadow: `0 -4px 12px ${glow}`,
              }} />
          );
        })}
      </div>
      <div style={{ display: "flex", gap: 5, marginTop: 3 }}>
        {vals.map((v, i) => {
          const { bar } = BAR_COLORS[i % BAR_COLORS.length];
          return (
            <div key={i} style={{
              flex: 1, textAlign: "center", fontSize: 9, color: bar,
              overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
              fontWeight: 600,
            }}>
              {v.label || `#${i + 1}`}
            </div>
          );
        })}
      </div>
    </>
  );
}

// ── TokenDisplay ─────────────────────────────────────────
function TokenDisplay({ tokens }) {
  if (!tokens || !tokens.length) return null;
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, padding: "6px 0" }}>
      {tokens.map((t, i) => (
        <span key={i} style={{
          background: C.bg2, borderRadius: 6, padding: "4px 10px",
          fontSize: 12, fontFamily: "JetBrains Mono, monospace",
          color: highlightTokenType(t.type),
          border: `1px solid ${C.brd}`,
          transition: "all .15s",
          boxShadow: `0 0 0 0 ${highlightTokenType(t.type)}`,
        }}
          onMouseEnter={e => {
            const c = highlightTokenType(t.type);
            e.currentTarget.style.borderColor = `${c}60`;
            e.currentTarget.style.boxShadow = `0 0 10px ${c}30`;
            e.currentTarget.style.textShadow = `0 0 8px ${c}60`;
          }}
          onMouseLeave={e => {
            e.currentTarget.style.borderColor = C.brd;
            e.currentTarget.style.boxShadow = "none";
            e.currentTarget.style.textShadow = "none";
          }}
        >{t.value || t.type}</span>
      ))}
    </div>
  );
}

// ── WalDisplay ───────────────────────────────────────────
function WalDisplay({ walLog }) {
  if (!walLog || walLog.length === 0) return (
    <div style={{
      color: C.txt3, fontSize: 13, padding: "24px 0",
      textAlign: "center",
    }}>
      <div style={{ fontSize: 28, marginBottom: 8, opacity: 0.3 }}>📋</div>
      No WAL entries yet.
    </div>
  );
  return (
    <div style={{ overflowX: "auto", borderRadius: 10, border: `1px solid ${C.brd}`, boxShadow: "0 4px 24px rgba(0,0,0,0.2)" }}>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12.5 }}>
        <thead>
          <tr style={{ background: "rgba(124,58,237,0.08)" }}>
            {["Seq", "Op", "Table", "Payload"].map(h => (
              <th key={h} style={{
                padding: "9px 14px", textAlign: h === "Seq" ? "right" : "left",
                color: "#c084fc", fontWeight: 700,
                borderBottom: `1px solid ${C.brd}`,
                fontSize: 10, letterSpacing: ".07em",
                textShadow: "0 0 10px rgba(192,132,252,0.3)",
              }}>{h.toUpperCase()}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {walLog.map((entry, i) => {
            const c = OP_COLORS[entry.operation] || C.txt2;
            return (
              <tr key={i} className="wal-row"
                style={{ borderBottom: `1px solid ${C.brd}`, transition: "background .12s" }}
                onMouseEnter={e => e.currentTarget.style.background = "rgba(124,58,237,0.05)"}
                onMouseLeave={e => e.currentTarget.style.background = "transparent"}
              >
                <td style={{ padding: "6px 14px", textAlign: "right", color: C.txt3, fontSize: 10, fontFamily: "monospace" }}>{entry.sequenceNumber}</td>
                <td style={{ padding: "6px 14px" }}>
                  <span style={{
                    color: c, fontWeight: 700, fontSize: 10,
                    padding: "2px 8px", borderRadius: 12,
                    background: `${c}15`, border: `1px solid ${c}35`,
                    boxShadow: `0 0 8px ${c}20`,
                    letterSpacing: ".05em",
                  }}>{entry.operation}</span>
                </td>
                <td style={{ padding: "6px 14px", color: "#38bdf8", fontSize: 12, textShadow: "0 0 6px rgba(56,189,248,0.3)" }}>{entry.tableName}</td>
                <td style={{
                  padding: "6px 14px", color: C.txt2, maxWidth: 260,
                  overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
                  fontSize: 11, fontFamily: "monospace",
                }}>{JSON.stringify(entry.payload)}</td>
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

  if (loading) return (
    <div style={{ color: C.txt3, fontSize: 13, padding: "24px 0", textAlign: "center" }}>
      <div style={{ fontSize: 20, animation: "spin .8s linear infinite", display: "inline-block", marginBottom: 8 }}>◌</div>
      <div>Loading history…</div>
    </div>
  );
  if (!entries.length) return (
    <div style={{ color: C.txt3, fontSize: 13, padding: "24px 0", textAlign: "center" }}>
      <div style={{ fontSize: 28, marginBottom: 8, opacity: 0.3 }}>📜</div>
      No query history yet.
    </div>
  );

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 7 }}>
      {entries.map((e, i) => (
        <div key={i} onClick={() => onRerun(e.sql)}
          className="row-anim card-hover"
          style={{
            background: C.bg2,
            border: `1px solid ${e.success ? "rgba(52,211,153,0.15)" : "rgba(248,113,113,0.15)"}`,
            borderLeft: `3px solid ${e.success ? "#34d399" : "#f87171"}`,
            borderRadius: 9, padding: "11px 15px", cursor: "pointer",
            boxShadow: `0 2px 12px ${e.success ? "rgba(52,211,153,0.06)" : "rgba(248,113,113,0.06)"}`,
            transition: "all .15s",
          }}
          onMouseEnter={ev => {
            ev.currentTarget.style.background = C.bg3;
            ev.currentTarget.style.boxShadow = `0 6px 24px ${e.success ? "rgba(52,211,153,0.12)" : "rgba(248,113,113,0.12)"}`;
          }}
          onMouseLeave={ev => {
            ev.currentTarget.style.background = C.bg2;
            ev.currentTarget.style.boxShadow = `0 2px 12px ${e.success ? "rgba(52,211,153,0.06)" : "rgba(248,113,113,0.06)"}`;
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", gap: 12, marginBottom: 6 }}>
            <code style={{
              fontSize: 12.5, color: e.success ? C.txt : "#f87171",
              whiteSpace: "pre-wrap", wordBreak: "break-all", flex: 1,
              fontFamily: "JetBrains Mono, monospace", lineHeight: 1.7,
            }}>{e.sql}</code>
            <div style={{ display: "flex", gap: 5, flexShrink: 0, alignItems: "center" }}>
              <span style={{
                fontSize: 10, padding: "2px 8px", borderRadius: 10, fontWeight: 700,
                background: e.success ? "rgba(52,211,153,0.12)" : "rgba(248,113,113,0.12)",
                color: e.success ? "#34d399" : "#f87171",
                border: `1px solid ${e.success ? "rgba(52,211,153,0.3)" : "rgba(248,113,113,0.3)"}`,
                boxShadow: e.success ? "0 0 8px rgba(52,211,153,0.2)" : "0 0 8px rgba(248,113,113,0.2)",
              }}>{e.success ? "✓" : "✗"}</span>
              <span style={{ fontSize: 10, color: "#a78bfa", fontFamily: "monospace" }}>{e.elapsedMs}ms</span>
            </div>
          </div>
          <div style={{ display: "flex", gap: 12, fontSize: 10, color: C.txt3 }}>
            <span>{new Date(e.timestamp).toLocaleString()}</span>
            {e.rowCount > 0 && (
              <span style={{ color: "#818cf8", textShadow: "0 0 6px rgba(129,140,248,0.3)" }}>
                {e.rowCount} row{e.rowCount !== 1 ? "s" : ""}
              </span>
            )}
            {e.message && <span>{e.message}</span>}
          </div>
        </div>
      ))}
    </div>
  );
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
  const [sidebarHistory, setSidebarHistory] = useState([]);
  const [queryCount, setQueryCount] = useState(0);

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

  const runQuery = async () => {
    const sql = activeTab.sql; if (!sql.trim()) return;
    setLoading(true);
    try {
      const r = await fetch(`${API}/query`, { method: "POST", headers: getHeaders(), body: JSON.stringify({ sql: sql.trim() }) });
      const d = await r.json(); setResult(d); setTxnActive(d.txnActive); setQueryCount(q => q + 1);
      if (d.success) { await loadSchema(); await loadIndexes(); await loadWal(); await loadSidebarHistory(); setResultTab("results"); }
    } catch {
      setResult({ success: false, error: "Cannot reach backend. Is it running on :8081?", columns: [], rows: [], tokens: [] });
    }
    setLoading(false);
  };

  const runTransactionCmd = async (cmd) => {
    setLoading(true);
    try {
      const r = await fetch(`${API}/query`, { method: "POST", headers: getHeaders(), body: JSON.stringify({ sql: cmd }) });
      const d = await r.json(); setResult(d); setTxnActive(d.txnActive); setQueryCount(q => q + 1); setResultTab("results");
    } catch (e) { setResult({ success: false, error: String(e), columns: [], rows: [], tokens: [] }); }
    setLoading(false);
  };

  const resetSchema = async () => {
    await fetch(`${API}/schema/reset`, { method: "POST", headers: { Authorization: `Bearer ${token}` } });
    await loadSchema(); await loadIndexes(); setResult(null);
  };

  const exportResult = (format, result) => {
    let content, filename, mime;
    if (format === "CSV") {
      const h = result.columns.join(",");
      const rows = result.rows.map(r => result.columns.map(c => { const v = r[c]; return v === null || v === undefined ? "" : (typeof v === "string" ? `"${v.replace(/"/g, '""')}"` : v); }).join(","));
      content = [h, ...rows].join("\n"); filename = "result.csv"; mime = "text/csv";
    } else if (format === "JSON") {
      content = JSON.stringify(result.rows, null, 2); filename = "result.json"; mime = "application/json";
    } else {
      const cols = result.columns.join(", ");
      const vals = result.rows.map(r => `(${result.columns.map(c => { const v = r[c]; return v === null ? "NULL" : (typeof v === "string" ? `'${v}'` : v); }).join(", ")})`);
      content = `INSERT INTO result (${cols}) VALUES\n${vals.join(",\n")};`; filename = "result.sql"; mime = "text/plain";
    }
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([content], { type: mime })); a.download = filename; a.click();
  };

  const planCostNode = (plan, depth) => {
    if (!plan) return null;
    const color = OP_COLORS[plan.operation] || "#9ca3af";
    return (
      <>
        <div style={{
          display: "flex", alignItems: "center", gap: 6,
          padding: "5px 8px", borderRadius: 6, marginBottom: 4,
          fontSize: 10, marginLeft: depth * 12,
          background: `${color}0f`, borderLeft: `2px solid ${color}`,
          boxShadow: `0 0 8px ${color}08`,
        }}>
          <span style={{
            fontWeight: 800, color, fontSize: 9,
            background: `${color}22`, padding: "1px 6px", borderRadius: 4,
            border: `1px solid ${color}35`,
            boxShadow: `0 0 6px ${color}25`,
          }}>
            {plan.operation}
          </span>
          <span style={{ flex: 1, color: C.txt3, fontSize: 10 }}>{plan.description}</span>
          {plan.stats?.cost !== undefined && <span style={{ fontSize: 9, color: C.txt3 }}>cost {plan.stats.cost}</span>}
        </div>
        {plan.children?.map((child, i) => planCostNode(child, depth + 1))}
      </>
    );
  };

  // ── RESULT TAB COLOURS ────────────────────────────────
  const RESULT_TAB_COLORS = {
    results: "#818cf8", plan: "#fbbf24", chart: "#34d399",
    tokens: "#f472b6", wal: "#fb923c", history: "#38bdf8",
  };

  // ── AUTH GATE ─────────────────────────────────────────
  if (!token) {
    return (
      <div data-theme={theme} style={{
        display: "flex", alignItems: "center", justifyContent: "center",
        minHeight: "100vh", background: C.bg0,
        fontFamily: "'Inter','JetBrains Mono',monospace",
        position: "relative", overflow: "hidden",
      }}>
        {/* Background decorative orbs */}
        <div style={{
          position: "absolute", top: "15%", left: "15%",
          width: 400, height: 400, borderRadius: "50%",
          background: "radial-gradient(circle, rgba(124,58,237,0.12) 0%, transparent 70%)",
          pointerEvents: "none",
        }} />
        <div style={{
          position: "absolute", bottom: "15%", right: "15%",
          width: 300, height: 300, borderRadius: "50%",
          background: "radial-gradient(circle, rgba(56,189,248,0.10) 0%, transparent 70%)",
          pointerEvents: "none",
        }} />
        <div style={{
          position: "absolute", top: "50%", right: "20%",
          width: 200, height: 200, borderRadius: "50%",
          background: "radial-gradient(circle, rgba(244,114,182,0.08) 0%, transparent 70%)",
          pointerEvents: "none",
        }} />

        <div style={{ width: 420, position: "relative", zIndex: 1 }}>
          {/* Logo & Title */}
          <div style={{ textAlign: "center", marginBottom: 36 }}>
            <div style={{
              margin: "0 auto 16px", width: 64, height: 64,
              animation: "glowPulse 3s ease infinite",
              borderRadius: "50%",
              display: "flex", alignItems: "center", justifyContent: "center",
            }}>
              <Logo size={56} />
            </div>
            <div style={{
              fontSize: 30, fontWeight: 800, letterSpacing: ".3px",
              background: "linear-gradient(135deg, #a78bfa 0%, #f472b6 50%, #38bdf8 100%)",
              WebkitBackgroundClip: "text", WebkitTextFillColor: "transparent",
              backgroundClip: "text",
              marginBottom: 6,
            }}>
              SQL Playground
            </div>
            <div style={{
              fontSize: 13, color: C.txt3, letterSpacing: ".04em",
              display: "flex", alignItems: "center", justifyContent: "center", gap: 8,
            }}>
              <span style={{ width: 24, height: 1, background: `${C.brd2}` }} />
              Custom Java Query Engine
              <span style={{ width: 24, height: 1, background: `${C.brd2}` }} />
            </div>
          </div>

          {/* Card */}
          <div style={{
            background: C.bg1,
            border: `1px solid ${C.brd2}`,
            borderRadius: 16,
            padding: "32px 30px 28px",
            boxShadow: "0 24px 80px rgba(0,0,0,0.4), 0 0 0 1px rgba(124,58,237,0.08), 0 0 40px rgba(124,58,237,0.08)",
          }}>
            {/* Tab switcher */}
            <div style={{
              display: "flex", background: C.bg2,
              borderRadius: 10, padding: 4, gap: 4, marginBottom: 28,
              border: `1px solid ${C.brd}`,
            }}>
              {["login", "signup"].map(view => (
                <button key={view} onClick={() => { setAuthView(view); setAuthError(""); }}
                  style={{
                    flex: 1, padding: "9px 0", fontSize: 13, fontWeight: 700, letterSpacing: ".04em",
                    background: authView === view
                      ? "linear-gradient(135deg, rgba(124,58,237,0.9) 0%, rgba(139,92,246,0.9) 100%)"
                      : "transparent",
                    color: authView === view ? "#fff" : C.txt3,
                    border: authView === view ? "1px solid rgba(167,139,250,0.4)" : "1px solid transparent",
                    borderRadius: 7, cursor: "pointer",
                    boxShadow: authView === view ? "0 0 16px rgba(124,58,237,0.4)" : "none",
                    transition: "all .2s",
                  }}
                >{view === "login" ? "Log in" : "Sign up"}</button>
              ))}
            </div>

            {/* Fields */}
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              {[
                { key: "username", label: "USERNAME", type: "text", ph: "your_username", icon: "◉" },
                { key: "password", label: "PASSWORD", type: "password", ph: "••••••••", icon: "◈" },
              ].map(({ key, label, type, ph, icon }) => (
                <div key={key}>
                  <div style={{
                    fontSize: 10, color: C.txt3, fontWeight: 800,
                    letterSpacing: ".12em", marginBottom: 7,
                    display: "flex", alignItems: "center", gap: 5,
                  }}>
                    <span style={{ color: C.accent2, fontSize: 12 }}>{icon}</span>
                    {label}
                  </div>
                  <input type={type} value={authForm[key]} placeholder={ph}
                    onChange={e => setAuthForm(f => ({ ...f, [key]: e.target.value }))}
                    onKeyDown={e => e.key === "Enter" && (authView === "login" ? doLogin() : doSignup())}
                    style={{
                      width: "100%", padding: "11px 14px",
                      background: C.bg2, color: C.txt, fontSize: 13.5,
                      border: `1px solid ${C.brd2}`, borderRadius: 8,
                      outline: "none", boxSizing: "border-box",
                      fontFamily: "JetBrains Mono, monospace",
                      letterSpacing: ".03em",
                    }}
                    onFocus={e => {
                      e.target.style.borderColor = "rgba(124,58,237,0.6)";
                      e.target.style.boxShadow = "0 0 0 3px rgba(124,58,237,0.12), 0 0 16px rgba(124,58,237,0.2)";
                    }}
                    onBlur={e => {
                      e.target.style.borderColor = C.brd2;
                      e.target.style.boxShadow = "none";
                    }}
                  />
                </div>
              ))}

              {authError && (
                <div style={{
                  fontSize: 12.5, color: "#f87171", padding: "10px 14px", borderRadius: 8,
                  background: "rgba(248,113,113,0.08)", border: "1px solid rgba(248,113,113,0.25)",
                  boxShadow: "0 0 12px rgba(248,113,113,0.12)",
                  display: "flex", alignItems: "center", gap: 7,
                }}>
                  <span style={{ fontSize: 16 }}>⚠</span>
                  {authError}
                </div>
              )}

              <button
                onClick={authView === "login" ? doLogin : doSignup}
                disabled={authLoading}
                style={{
                  marginTop: 4, padding: "12px 0", borderRadius: 9,
                  background: authLoading
                    ? C.bg3
                    : "linear-gradient(135deg, #7c3aed 0%, #a78bfa 50%, #38bdf8 100%)",
                  backgroundSize: "200% auto",
                  color: authLoading ? C.txt3 : "#fff",
                  border: "none", fontSize: 14, fontWeight: 700,
                  cursor: authLoading ? "not-allowed" : "pointer",
                  letterSpacing: ".06em",
                  boxShadow: authLoading ? "none" : "0 0 24px rgba(124,58,237,0.4), 0 4px 20px rgba(0,0,0,0.2)",
                  transition: "all .25s",
                }}
                onMouseEnter={e => { if (!authLoading) { e.currentTarget.style.backgroundPosition = "right center"; e.currentTarget.style.boxShadow = "0 0 36px rgba(124,58,237,0.6), 0 8px 28px rgba(0,0,0,0.25)"; e.currentTarget.style.transform = "translateY(-1px)"; } }}
                onMouseLeave={e => { if (!authLoading) { e.currentTarget.style.backgroundPosition = "left center"; e.currentTarget.style.boxShadow = "0 0 24px rgba(124,58,237,0.4), 0 4px 20px rgba(0,0,0,0.2)"; e.currentTarget.style.transform = "translateY(0)"; } }}
              >
                {authLoading
                  ? <span style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 8 }}>
                    <span className="spin" style={{ display: "inline-block" }}>◌</span> Please wait…
                  </span>
                  : authView === "login" ? "Log in →" : "Create account →"
                }
              </button>
            </div>

            {authView === "signup" && (
              <div style={{
                fontSize: 11, color: C.txt3, textAlign: "center",
                marginTop: 18, lineHeight: 1.7,
                padding: "8px 12px", borderRadius: 7,
                background: "rgba(124,58,237,0.05)",
                border: `1px solid ${C.brd}`,
              }}>
                3+ chars · letters, numbers, underscores
              </div>
            )}
          </div>

          {/* Footer */}
          <div style={{
            textAlign: "center", marginTop: 20,
            display: "flex", alignItems: "center", justifyContent: "center", gap: 14,
          }}>
            <span style={{ fontSize: 11, color: C.txt3 }}>
              Java lexer · parser · executor
            </span>
            <button onClick={toggleTheme} style={{
              fontSize: 11, background: "transparent",
              border: `1px solid ${C.brd2}`, borderRadius: 7,
              padding: "4px 12px", color: C.txt3, cursor: "pointer",
              transition: "all .15s",
            }}
              onMouseEnter={e => { e.currentTarget.style.borderColor = C.brd3; e.currentTarget.style.color = C.txt2; }}
              onMouseLeave={e => { e.currentTarget.style.borderColor = C.brd2; e.currentTarget.style.color = C.txt3; }}
            >{isDark ? "☀ Light" : "◑ Dark"}</button>
          </div>
        </div>
      </div>
    );
  }

  // ── MAIN PLAYGROUND ───────────────────────────────────
  return (
    <div data-theme={theme} style={{
      background: C.bg0, height: "100vh",
      display: "flex", flexDirection: "column",
      fontFamily: "'Inter','JetBrains Mono',monospace",
      color: C.txt, overflow: "hidden",
    }}>

      {/* ══ TOPBAR ══════════════════════════════════════ */}
      <div style={{
        background: C.bg1,
        borderBottom: `1px solid ${C.brd}`,
        padding: "0 18px", height: 52,
        display: "flex", alignItems: "center", gap: 14, flexShrink: 0,
        boxShadow: "0 1px 20px rgba(0,0,0,0.2)",
      }}>
        {/* Logo */}
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Logo size={26} />
          <span style={{
            fontSize: 15, fontWeight: 800, letterSpacing: ".3px",
            background: "linear-gradient(135deg, #a78bfa 0%, #38bdf8 100%)",
            WebkitBackgroundClip: "text", WebkitTextFillColor: "transparent", backgroundClip: "text",
          }}>
            SQL Playground
          </span>
          <span style={{
            fontSize: 9, padding: "2px 8px", borderRadius: 5,
            background: "rgba(124,58,237,0.12)", color: "#a78bfa",
            border: `1px solid rgba(124,58,237,0.3)`,
            letterSpacing: ".06em", fontWeight: 700,
            boxShadow: "0 0 8px rgba(124,58,237,0.15)",
          }}>Java Engine</span>
        </div>

        <div style={{ flex: 1 }} />

        {/* Backend down warning */}
        {backendDown && (
          <span className="backend-offline" style={{
            fontSize: 11, color: "#f87171",
            background: "rgba(248,113,113,0.08)",
            border: "1px solid rgba(248,113,113,0.25)",
            borderRadius: 6, padding: "4px 12px",
            boxShadow: "0 0 12px rgba(248,113,113,0.15)",
            display: "flex", alignItems: "center", gap: 6,
          }}>
            <span>⚠</span> Backend offline
          </span>
        )}

        {/* Theme toggle */}
        <div style={{ display: "flex", alignItems: "center", gap: 7 }}>
          <span style={{ fontSize: 10, color: C.txt3 }}>theme</span>
          <div onClick={toggleTheme} style={{
            width: 34, height: 19, background: C.bg3,
            border: `1px solid ${C.brd2}`, borderRadius: 10,
            position: "relative", cursor: "pointer",
            boxShadow: `0 0 8px ${isDark ? "rgba(251,191,36,0.2)" : "rgba(124,58,237,0.2)"}`,
          }}>
            <div style={{
              width: 15, height: 15, borderRadius: "50%",
              background: isDark ? "#fbbf24" : "#818cf8",
              position: "absolute", top: 1,
              left: isDark ? 1 : 16,
              boxShadow: isDark ? "0 0 8px rgba(251,191,36,0.6)" : "0 0 8px rgba(129,140,248,0.6)",
              transition: "left .2s, background .2s, box-shadow .2s",
            }} />
          </div>
        </div>

        {/* Reset */}
        <button onClick={resetSchema} style={{
          fontSize: 11, color: C.txt3, background: "transparent",
          border: `1px solid ${C.brd}`, borderRadius: 7,
          padding: "5px 13px", cursor: "pointer", fontWeight: 600,
          letterSpacing: ".03em",
        }}
          onMouseEnter={e => { e.currentTarget.style.color = "#f87171"; e.currentTarget.style.borderColor = "rgba(248,113,113,0.35)"; e.currentTarget.style.boxShadow = "0 0 10px rgba(248,113,113,0.12)"; }}
          onMouseLeave={e => { e.currentTarget.style.color = C.txt3; e.currentTarget.style.borderColor = C.brd; e.currentTarget.style.boxShadow = "none"; }}
        >Reset data</button>

        {/* User chip */}
        <div style={{
          display: "flex", alignItems: "center", gap: 8,
          background: "rgba(124,58,237,0.08)",
          border: `1px solid rgba(124,58,237,0.25)`,
          borderRadius: 24, padding: "4px 12px 4px 4px",
          boxShadow: "0 0 16px rgba(124,58,237,0.08)",
        }}>
          <div className="avatar-ring" style={{
            width: 28, height: 28, borderRadius: "50%",
            background: "linear-gradient(135deg, #7c3aed 0%, #38bdf8 100%)",
            display: "flex", alignItems: "center", justifyContent: "center",
            fontSize: 10, color: "#fff", fontWeight: 800, letterSpacing: ".04em",
          }}>{username.slice(0, 2).toUpperCase()}</div>
          <span style={{ fontSize: 12, color: "#a78bfa", fontWeight: 700 }}>{username}</span>
          <div style={{
            width: 7, height: 7, borderRadius: "50%",
            background: "#34d399",
            boxShadow: "0 0 8px rgba(52,211,153,0.7)",
            animation: "pulse 2s ease infinite",
          }} />
        </div>

        {/* Logout */}
        <button onClick={doLogout} style={{
          fontSize: 11, color: C.txt3, background: "transparent",
          border: `1px solid ${C.brd}`, borderRadius: 7,
          padding: "5px 13px", cursor: "pointer", fontWeight: 600,
        }}
          onMouseEnter={e => { e.currentTarget.style.color = "#f87171"; e.currentTarget.style.borderColor = "rgba(248,113,113,0.35)"; e.currentTarget.style.background = "rgba(248,113,113,0.05)"; }}
          onMouseLeave={e => { e.currentTarget.style.color = C.txt3; e.currentTarget.style.borderColor = C.brd; e.currentTarget.style.background = "transparent"; }}
        >Log out</button>
      </div>

      {/* ══ MAIN AREA ════════════════════════════════════ */}
      <div style={{ display: "flex", flex: 1, overflow: "hidden" }}>

        {/* ── Sidebar ──────────────────────────────────── */}
        <div style={{
          width: 215, flexShrink: 0,
          borderRight: `1px solid ${C.brd}`,
          display: "flex", flexDirection: "column",
          overflow: "hidden",
        }}>
          <SchemaPanel
            schema={schema}
            indexedKeys={indexedKeys}
            onTableClick={q => updateTabSql(q)}
            onQuerySelect={q => updateTabSql(q)}
            history={sidebarHistory}
          />
        </div>

        {/* ── Center ───────────────────────────────────── */}
        <div style={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>

          {/* Multi-tab bar */}
          <div style={{
            background: C.bg1, borderBottom: `1px solid ${C.brd}`,
            display: "flex", alignItems: "center",
            padding: "0 12px", gap: 1, flexShrink: 0, overflowX: "auto",
          }}>
            {tabs.map(tab => (
              <div key={tab.id} onClick={() => setActiveTabId(tab.id)}
                className="qtab-wrap"
                style={{
                  padding: "9px 13px", fontSize: 11.5, cursor: "pointer",
                  borderBottom: `2px solid ${activeTabId === tab.id ? "#a78bfa" : "transparent"}`,
                  color: activeTabId === tab.id ? "#a78bfa" : C.txt3,
                  whiteSpace: "nowrap", display: "flex", alignItems: "center", gap: 6,
                  background: activeTabId === tab.id ? "rgba(124,58,237,0.08)" : "transparent",
                  textShadow: activeTabId === tab.id ? "0 0 10px rgba(167,139,250,0.4)" : "none",
                  fontWeight: activeTabId === tab.id ? 600 : 400,
                  transition: "all .15s",
                }}
              >
                <div style={{
                  width: 6, height: 6, borderRadius: "50%",
                  background: activeTabId === tab.id ? "#a78bfa" : C.grey,
                  boxShadow: activeTabId === tab.id ? "0 0 8px rgba(167,139,250,0.7)" : "none",
                  flexShrink: 0, transition: "all .15s",
                }} />
                {tab.label}
                <span onClick={(e) => closeTab(tab.id, e)}
                  className="tab-close-btn"
                  style={{
                    color: "transparent", fontSize: 13,
                    width: 16, height: 16, display: "flex", alignItems: "center",
                    justifyContent: "center", borderRadius: 4,
                    transition: "all .15s", marginLeft: 2,
                  }}
                >×</span>
              </div>
            ))}
            <div onClick={addTab}
              style={{
                padding: "6px 10px", fontSize: 20, color: C.txt3,
                cursor: "pointer", transition: "all .15s",
                lineHeight: 1,
              }}
              onMouseEnter={e => { e.currentTarget.style.color = "#a78bfa"; e.currentTarget.style.textShadow = "0 0 10px rgba(167,139,250,0.5)"; }}
              onMouseLeave={e => { e.currentTarget.style.color = C.txt3; e.currentTarget.style.textShadow = "none"; }}
            >+</div>
          </div>

          {/* Example queries bar */}
          <div className="examples-bar" style={{
            display: "flex", gap: 5, padding: "7px 15px",
            background: C.bg1, borderBottom: `1px solid ${C.brd}`,
            overflowX: "auto", flexShrink: 0, alignItems: "center",
          }}>
            <span style={{
              fontSize: 9, color: C.txt3, whiteSpace: "nowrap",
              fontWeight: 800, letterSpacing: ".10em", marginRight: 5,
              textTransform: "uppercase",
            }}>Examples:</span>
            {SAMPLE_QUERIES.map(q => (
              <button key={q.label} onClick={() => updateTabSql(q.sql)} style={{
                fontSize: 10.5, whiteSpace: "nowrap", color: C.txt2,
                background: C.bg2, border: `1px solid ${C.brd}`,
                borderRadius: 6, padding: "4px 10px", cursor: "pointer",
                fontWeight: 500, letterSpacing: ".02em",
                transition: "all .15s",
              }}
                onMouseEnter={e => {
                  e.currentTarget.style.color = "#a78bfa";
                  e.currentTarget.style.borderColor = "rgba(124,58,237,0.35)";
                  e.currentTarget.style.background = "rgba(124,58,237,0.08)";
                  e.currentTarget.style.boxShadow = "0 0 10px rgba(124,58,237,0.12)";
                }}
                onMouseLeave={e => {
                  e.currentTarget.style.color = C.txt2;
                  e.currentTarget.style.borderColor = C.brd;
                  e.currentTarget.style.background = C.bg2;
                  e.currentTarget.style.boxShadow = "none";
                }}
              >{q.label}</button>
            ))}
          </div>

          {/* Editor */}
          <div style={{
            background: C.bg2, borderBottom: `1px solid ${C.brd}`,
            padding: "8px 14px 0", flexShrink: 0,
          }}>
            {/* Editor header */}
            <div style={{
              display: "flex", alignItems: "center", justifyContent: "space-between",
              padding: "5px 6px 8px",
            }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                {[
                  { c: "#f87171", s: "0 0 8px rgba(248,113,113,0.6)" },
                  { c: "#fbbf24", s: "0 0 8px rgba(251,191,36,0.6)" },
                  { c: "#34d399", s: "0 0 8px rgba(52,211,153,0.6)" },
                ].map((dot, i) => (
                  <div key={i} style={{ width: 11, height: 11, borderRadius: "50%", background: dot.c, boxShadow: dot.s }} />
                ))}
                <span style={{ fontSize: 9, color: C.txt3, marginLeft: 8, letterSpacing: ".10em", fontWeight: 800, textTransform: "uppercase" }}>SQL Editor</span>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                <span style={{ fontSize: 10, color: C.txt3, letterSpacing: ".02em" }}>
                  <kbd style={{
                    background: C.bg3, border: `1px solid ${C.brd2}`,
                    borderRadius: 4, padding: "1px 6px", fontSize: 10,
                    color: C.txt3, fontFamily: "inherit",
                  }}>Ctrl</kbd>+<kbd style={{
                    background: C.bg3, border: `1px solid ${C.brd2}`,
                    borderRadius: 4, padding: "1px 6px", fontSize: 10,
                    color: C.txt3, fontFamily: "inherit",
                  }}>↵</kbd> to run
                </span>
                <button onClick={runQuery} disabled={loading} style={{
                  background: loading
                    ? C.bg3
                    : "linear-gradient(135deg, #7c3aed 0%, #a78bfa 60%, #38bdf8 100%)",
                  backgroundSize: "200% auto",
                  color: loading ? C.txt3 : "#fff",
                  border: "none", borderRadius: 7, padding: "6px 18px",
                  fontSize: 12.5, fontWeight: 700, cursor: loading ? "not-allowed" : "pointer",
                  display: "flex", alignItems: "center", gap: 6,
                  boxShadow: loading ? "none" : "0 0 18px rgba(124,58,237,0.45), 0 4px 12px rgba(0,0,0,0.2)",
                  opacity: loading ? 0.7 : 1,
                  letterSpacing: ".04em",
                  transition: "all .2s",
                }}
                  onMouseEnter={e => { if (!loading) { e.currentTarget.style.backgroundPosition = "right center"; e.currentTarget.style.boxShadow = "0 0 28px rgba(124,58,237,0.65), 0 6px 18px rgba(0,0,0,0.25)"; e.currentTarget.style.transform = "translateY(-1px)"; } }}
                  onMouseLeave={e => { if (!loading) { e.currentTarget.style.backgroundPosition = "left center"; e.currentTarget.style.boxShadow = "0 0 18px rgba(124,58,237,0.45), 0 4px 12px rgba(0,0,0,0.2)"; e.currentTarget.style.transform = "translateY(0)"; } }}
                >
                  {loading
                    ? <><span className="spin" style={{ display: "inline-block" }}>◌</span> Running…</>
                    : <>▶ Run</>
                  }
                </button>
              </div>
            </div>
            <SqlEditor
              value={activeTab.sql}
              onChange={updateTabSql}
              onRun={runQuery}
              loading={loading}
              schema={schema}
            />
          </div>

          {/* Toolbar */}
          <div style={{
            background: C.bg1, borderBottom: `1px solid ${C.brd}`,
            padding: "7px 15px", display: "flex", alignItems: "center",
            gap: 8, flexShrink: 0,
          }}>
            {/* TXN status */}
            <div className={txnActive ? "txn-active-badge" : ""} style={{
              display: "flex", alignItems: "center", gap: 7,
              padding: "4px 12px", borderRadius: 8,
              background: txnActive ? "rgba(251,191,36,0.08)" : C.bg2,
              border: `1px solid ${txnActive ? "rgba(251,191,36,0.35)" : C.brd}`,
              boxShadow: txnActive ? "0 0 14px rgba(251,191,36,0.15)" : "none",
              transition: "all .3s",
            }}>
              <div style={{
                width: 7, height: 7, borderRadius: "50%",
                background: txnActive ? "#fbbf24" : C.grey,
                boxShadow: txnActive ? "0 0 8px rgba(251,191,36,0.8)" : "none",
                transition: "all .3s",
              }} />
              <span style={{
                fontSize: 10, fontWeight: 800, letterSpacing: ".07em",
                color: txnActive ? "#fbbf24" : C.txt3,
                textShadow: txnActive ? "0 0 8px rgba(251,191,36,0.4)" : "none",
                transition: "all .3s",
              }}>{txnActive ? "TXN ACTIVE" : "AUTO-COMMIT"}</span>
            </div>

            {/* Transaction buttons */}
            {[
              { label: "BEGIN", cmd: "BEGIN", enabled: !txnActive, color: "#34d399", glow: "rgba(52,211,153,0.35)" },
              { label: "COMMIT", cmd: "COMMIT", enabled: txnActive, color: "#818cf8", glow: "rgba(129,140,248,0.35)" },
              { label: "ROLLBACK", cmd: "ROLLBACK", enabled: txnActive, color: "#f87171", glow: "rgba(248,113,113,0.35)" },
            ].map(({ label, cmd, enabled, color, glow }) => (
              <button key={label}
                onClick={() => enabled && runTransactionCmd(cmd)}
                style={{
                  fontSize: 10, padding: "5px 14px", borderRadius: 7,
                  fontWeight: 800, letterSpacing: ".06em",
                  background: enabled ? `${color}10` : "transparent",
                  color: enabled ? color : C.txt4,
                  border: `1px solid ${enabled ? `${color}35` : C.brd}`,
                  cursor: enabled ? "pointer" : "not-allowed",
                  boxShadow: enabled ? `0 0 10px ${glow}` : "none",
                  opacity: enabled ? 1 : 0.4,
                  textShadow: enabled ? `0 0 8px ${glow}` : "none",
                  transition: "all .2s",
                }}
                onMouseEnter={e => { if (enabled) e.currentTarget.style.boxShadow = `0 0 18px ${glow}`; }}
                onMouseLeave={e => { if (enabled) e.currentTarget.style.boxShadow = `0 0 10px ${glow}`; }}
              >{label}</button>
            ))}

            <div style={{ flex: 1 }} />
            <span style={{ fontSize: 9, color: C.txt4, fontFamily: "JetBrains Mono, monospace" }}>
              {sessionId.slice(0, 12)}…
            </span>
          </div>

          {/* Result tabs */}
          <div style={{
            display: "flex", background: C.bg1,
            borderBottom: `1px solid ${C.brd}`,
            padding: "0 15px", flexShrink: 0, alignItems: "center",
          }}>
            {["results", "plan", "chart", "tokens", "wal", "history"].map(tab => {
              const color = RESULT_TAB_COLORS[tab];
              return (
                <button key={tab} onClick={() => setResultTab(tab)} style={{
                  padding: "9px 15px", fontSize: 11.5, fontWeight: resultTab === tab ? 700 : 500,
                  background: "transparent", border: "none",
                  borderBottom: `2px solid ${resultTab === tab ? color : "transparent"}`,
                  color: resultTab === tab ? color : C.txt3,
                  cursor: "pointer", textTransform: "capitalize",
                  letterSpacing: ".03em",
                  textShadow: resultTab === tab ? `0 0 10px ${color}50` : "none",
                  transition: "all .15s",
                }}>{tab}</button>
              );
            })}

            <div style={{ flex: 1 }} />

            {/* Status + export */}
            {result && (
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                {result.success && (
                  <span className="pill-success" style={{
                    fontSize: 10, padding: "2px 10px", borderRadius: 12, fontWeight: 700,
                    background: "rgba(52,211,153,0.10)", color: "#34d399",
                    border: "1px solid rgba(52,211,153,0.25)",
                    boxShadow: "0 0 10px rgba(52,211,153,0.2)",
                  }}>
                    ✓ {result.columns.length > 0 ? `${result.rows.length} rows` : result.message}
                  </span>
                )}
                <span style={{ fontSize: 10, color: "#818cf8", textShadow: "0 0 8px rgba(129,140,248,0.4)", fontFamily: "JetBrains Mono, monospace" }}>
                  {result.elapsedMs}ms
                </span>
                {result.success && result.columns?.length > 0 && (
                  <div style={{ display: "flex", gap: 4 }}>
                    {[
                      ["CSV", "rgba(52,211,153,0.12)", "rgba(52,211,153,0.3)", "#34d399"],
                      ["JSON", "rgba(129,140,248,0.12)", "rgba(129,140,248,0.3)", "#818cf8"],
                      ["SQL", "rgba(251,191,36,0.12)", "rgba(251,191,36,0.3)", "#fbbf24"],
                    ].map(([fmt, bg, brd, col]) => (
                      <button key={fmt} onClick={() => exportResult(fmt, result)} style={{
                        fontSize: 9, padding: "2px 8px", borderRadius: 5,
                        background: bg, color: col,
                        border: `1px solid ${brd}`, cursor: "pointer",
                        fontWeight: 800, letterSpacing: ".04em",
                        boxShadow: `0 0 6px ${brd}`,
                        transition: "all .15s",
                      }}
                        onMouseEnter={e => { e.currentTarget.style.opacity = "0.75"; e.currentTarget.style.transform = "translateY(-1px)"; }}
                        onMouseLeave={e => { e.currentTarget.style.opacity = "1"; e.currentTarget.style.transform = "translateY(0)"; }}
                      >{fmt}</button>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Result area */}
          <div style={{ flex: 1, overflow: "auto", padding: "14px 16px" }}>
            {!result && (
              <div style={{
                textAlign: "center", color: C.txt4, marginTop: 60, fontSize: 14,
                lineHeight: 2,
              }}>
                <div style={{ fontSize: 36, marginBottom: 12, opacity: 0.2 }}>⬡</div>
                <div style={{ color: C.txt3 }}>Run a query to see results</div>
                <div style={{ fontSize: 11, color: C.txt4, marginTop: 6 }}>
                  <kbd style={{ background: C.bg3, border: `1px solid ${C.brd2}`, borderRadius: 4, padding: "1px 6px", fontSize: 10, color: C.txt3 }}>Ctrl</kbd>+<kbd style={{ background: C.bg3, border: `1px solid ${C.brd2}`, borderRadius: 4, padding: "1px 6px", fontSize: 10, color: C.txt3 }}>↵</kbd>
                </div>
              </div>
            )}
            {result && !result.success && result.error && (
              <div style={{
                background: "rgba(248,113,113,0.07)",
                border: "1px solid rgba(248,113,113,0.22)",
                borderLeft: "4px solid #f87171",
                borderRadius: 9, padding: "14px 18px",
                color: "#f87171", fontSize: 13.5,
                boxShadow: "0 4px 20px rgba(248,113,113,0.08)",
                animation: "fadeIn .18s ease",
                lineHeight: 1.7,
              }}>
                <span style={{ fontWeight: 800, letterSpacing: ".03em" }}>Error: </span>{result.error}
              </div>
            )}
            {result && result.success && result.columns.length === 0 && (
              <div style={{
                color: "#34d399", fontSize: 13.5, padding: "10px 0",
                display: "flex", alignItems: "center", gap: 10,
                animation: "fadeIn .18s ease",
                textShadow: "0 0 10px rgba(52,211,153,0.3)",
              }}>
                <div style={{
                  width: 8, height: 8, borderRadius: "50%",
                  background: "#34d399", boxShadow: "0 0 10px rgba(52,211,153,0.7)",
                }} />
                {result.message}
              </div>
            )}
            {result && result.success && result.columns.length > 0 && resultTab === "results" && (
              <div style={{ animation: "fadeIn .18s ease" }}>
                <ResultGrid columns={result.columns} rows={result.rows} />
              </div>
            )}
            {result && resultTab === "plan" && (
              <div style={{ animation: "fadeIn .18s ease" }}>
                <div style={{ fontSize: 11.5, color: C.txt3, marginBottom: 12, lineHeight: 1.7 }}>
                  Execution plan — tree view of query operations.
                </div>
                {result.plan ? <PlanNodeDisplay node={result.plan} /> : <div style={{ color: C.txt3 }}>No plan available.</div>}
              </div>
            )}
            {result && resultTab === "chart" && (
              <div style={{ animation: "fadeIn .18s ease" }}>
                <div style={{ fontSize: 11.5, color: C.txt3, marginBottom: 12 }}>Auto chart — first numeric column visualized.</div>
                <AutoChart columns={result.columns} rows={result.rows} />
              </div>
            )}
            {result && resultTab === "tokens" && (
              <div style={{ animation: "fadeIn .18s ease" }}>
                <div style={{ fontSize: 11.5, color: C.txt3, marginBottom: 8 }}>Token stream from the lexer.</div>
                <TokenDisplay tokens={result.tokens} />
              </div>
            )}
            {result && resultTab === "wal" && (
              <div style={{ animation: "fadeIn .18s ease" }}>
                <div style={{ fontSize: 11.5, color: C.txt3, marginBottom: 12 }}>Write-Ahead Log — mutations recorded before execution.</div>
                <WalDisplay walLog={walLog} />
              </div>
            )}
            {result && resultTab === "history" && (
              <div style={{ animation: "fadeIn .18s ease" }}>
                <HistoryPanel token={token} onRerun={sql => updateTabSql(sql)} />
              </div>
            )}
          </div>

          {/* Status bar */}
          <div style={{
            background: C.bg1, borderTop: `1px solid ${C.brd}`,
            padding: "5px 15px", display: "flex", alignItems: "center",
            gap: 12, fontSize: 10, color: C.txt3, flexShrink: 0,
          }}>
            {result?.plan?.operation === "INDEX_SCAN" && (
              <span style={{
                color: "#38bdf8", fontSize: 9, fontWeight: 700,
                textShadow: "0 0 8px rgba(56,189,248,0.5)", letterSpacing: ".05em",
              }}>⚡ INDEX_SCAN</span>
            )}
            {result?.success && (
              <span style={{
                color: "#34d399",
                textShadow: "0 0 8px rgba(52,211,153,0.3)",
              }}>
                ✓ {result.columns.length > 0 ? `${result.rows.length} row${result.rows.length !== 1 ? "s" : ""} returned` : result.message}
              </span>
            )}
            {result && (
              <span style={{ color: "#818cf8", fontFamily: "JetBrains Mono, monospace", textShadow: "0 0 8px rgba(129,140,248,0.3)" }}>
                {result.elapsedMs}ms
              </span>
            )}
            <div style={{ flex: 1 }} />
            {queryCount > 0 && (
              <span className="query-count-fade" style={{ color: C.txt4, fontSize: 9, fontFamily: "JetBrains Mono, monospace" }}>
                {queryCount} quer{queryCount !== 1 ? "ies" : "y"} run
              </span>
            )}
          </div>
        </div>

        {/* ── Right panel ─────────────────────────────── */}
        <div style={{
          width: 228, background: C.bg1,
          borderLeft: `1px solid ${C.brd}`,
          display: "flex", flexDirection: "column",
          overflow: "auto", flexShrink: 0,
        }}>

          {/* Execution plan */}
          <div style={{ padding: "12px 13px", borderBottom: `1px solid ${C.brd}` }}>
            <div style={{
              fontSize: 9, letterSpacing: ".10em", color: "#a78bfa",
              textTransform: "uppercase", fontWeight: 800, marginBottom: 10,
              display: "flex", alignItems: "center", gap: 6,
              textShadow: "0 0 10px rgba(167,139,250,0.4)",
            }}>
              <span>⬡</span> Execution Plan
            </div>
            {result?.plan
              ? planCostNode(result.plan, 0)
              : <div style={{ fontSize: 11, color: C.txt3, lineHeight: 1.7 }}>Run a query to see the plan.</div>
            }
            {result?.elapsedMs !== undefined && (
              <div style={{ marginTop: 12 }}>
                <div style={{
                  fontSize: 9, letterSpacing: ".10em", color: "#fbbf24",
                  textTransform: "uppercase", fontWeight: 800, marginBottom: 7,
                  display: "flex", alignItems: "center", gap: 6,
                  textShadow: "0 0 10px rgba(251,191,36,0.4)",
                }}>
                  <span>▰</span> Flamegraph
                </div>
                <Flamegraph elapsedMs={result.elapsedMs} />
              </div>
            )}
          </div>

          {/* Auto chart */}
          <div style={{ padding: "12px 13px", borderBottom: `1px solid ${C.brd}` }}>
            <div style={{
              fontSize: 9, letterSpacing: ".10em", color: "#34d399",
              textTransform: "uppercase", fontWeight: 800, marginBottom: 10,
              display: "flex", alignItems: "center", gap: 6,
              textShadow: "0 0 10px rgba(52,211,153,0.4)",
            }}>
              <span>◈</span> Auto Chart
            </div>
            {result?.success && result?.columns?.length > 0 && result?.rows?.length > 0
              ? <AutoChart columns={result.columns} rows={result.rows} />
              : <div style={{ fontSize: 11, color: C.txt3, lineHeight: 1.7 }}>Run a SELECT to see a chart.</div>
            }
          </div>

          {/* Session panel */}
          <div style={{ padding: "12px 13px" }}>
            <div style={{
              fontSize: 9, letterSpacing: ".10em", color: "#38bdf8",
              textTransform: "uppercase", fontWeight: 800, marginBottom: 10,
              display: "flex", alignItems: "center", gap: 6,
              textShadow: "0 0 10px rgba(56,189,248,0.4)",
            }}>
              <span>◉</span> Session
            </div>
            <div style={{
              background: "rgba(56,189,248,0.06)",
              border: "1px solid rgba(56,189,248,0.18)",
              borderRadius: 8, padding: "10px 11px",
              boxShadow: "0 0 16px rgba(56,189,248,0.05)",
            }}>
              <div style={{
                fontSize: 10, color: "#38bdf8", marginBottom: 4, fontWeight: 700,
                display: "flex", alignItems: "center", justifyContent: "space-between",
                fontFamily: "JetBrains Mono, monospace",
              }}>
                <span style={{ textShadow: "0 0 8px rgba(56,189,248,0.4)" }}>{sessionId.slice(0, 13)}…</span>
                <span style={{
                  color: "#34d399", fontSize: 9,
                  display: "flex", alignItems: "center", gap: 4,
                }}>
                  <span style={{
                    width: 5, height: 5, borderRadius: "50%",
                    background: "#34d399", display: "inline-block",
                    boxShadow: "0 0 6px rgba(52,211,153,0.7)",
                    animation: "pulse 2s ease infinite",
                  }} />
                  active
                </span>
              </div>
              <div style={{ fontSize: 10, color: C.txt3, marginBottom: 3 }}>
                {txnActive ? `Txn #${(queryCount % 10) + 1} in progress` : "Auto-commit mode"}
              </div>
              <div style={{ fontSize: 10, color: C.txt3 }}>
                {queryCount > 0 ? `${queryCount} quer${queryCount !== 1 ? "ies" : "y"} run` : "No queries yet"}
              </div>
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}