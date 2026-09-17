import { useState, useEffect, useRef, useMemo } from "react";
import Editor, { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import {
  buildBaseCandidates,
  getCompletionContext,
  getRecentIdentifiers,
  rankCompletions,
} from "./sqlComplete.js";

loader.config({ monaco });

// Incremental local completion: first keystroke opens, every keystroke
// re-filters in JS (no debounce). Candidates memoized per schema.
const TRIGGER_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_.".split("");

function definePlaygroundThemes(m) {
  m.editor.defineTheme("sql-playground-light", {
    base: "vs",
    inherit: true,
    rules: [
      { token: "keyword", foreground: "227978", fontStyle: "bold" },
      { token: "string", foreground: "6C1A1A" },
      { token: "number", foreground: "7A5B00" },
      { token: "identifier", foreground: "26251F" },
      { token: "comment", foreground: "857F73", fontStyle: "italic" },
    ],
    colors: {
      "editor.background": "#EEE9DC",
      "editor.foreground": "#26251F",
      "editorCursor.foreground": "#31AAA9",
      "editor.lineHighlightBackground": "#E3DCCB66",
      "editorLineNumber.foreground": "#AEA897",
      "editorLineNumber.activeForeground": "#57544B",
      "editor.selectionBackground": "#31AAA955",
      "editorSuggestWidget.background": "#FCFBF7",
      "editorSuggestWidget.border": "#BFB5A0",
      "editorSuggestWidget.foreground": "#26251F",
      "editorSuggestWidget.selectedBackground": "#31AAA9",
      "editorSuggestWidget.selectedForeground": "#0E2423",
      "editorSuggestWidget.highlightForeground": "#227978",
      "editorSuggestWidget.focusHighlightForeground": "#0E2423",
      "editorWidget.border": "#BFB5A0",
    },
  });
  m.editor.defineTheme("sql-playground-dark", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "keyword", foreground: "5CC7C6", fontStyle: "bold" },
      { token: "string", foreground: "E8C48A" },
      { token: "number", foreground: "E8C48A" },
      { token: "identifier", foreground: "E9E1D6" },
      { token: "comment", foreground: "7F766B", fontStyle: "italic" },
    ],
    colors: {
      "editor.background": "#271D1E",
      "editor.foreground": "#E9E1D6",
      "editorCursor.foreground": "#31AAA9",
      "editor.lineHighlightBackground": "#35272766",
      "editorLineNumber.foreground": "#4F4843",
      "editorLineNumber.activeForeground": "#B7AC9D",
      "editor.selectionBackground": "#31AAA955",
      "editorSuggestWidget.background": "#1D1617",
      "editorSuggestWidget.border": "#5C3232",
      "editorSuggestWidget.foreground": "#E9E1D6",
      "editorSuggestWidget.selectedBackground": "#31AAA9",
      "editorSuggestWidget.selectedForeground": "#0E2423",
      "editorSuggestWidget.highlightForeground": "#5CC7C6",
      "editorSuggestWidget.focusHighlightForeground": "#0E2423",
      "editorWidget.border": "#5C3232",
    },
  });
}

function kindFor(monacoInstance, type) {
  const K = monacoInstance.languages.CompletionItemKind;
  if (type === "table") return K.Class;
  if (type === "column") return K.Field;
  if (type === "function") return K.Function;
  if (type === "snippet") return K.Snippet;
  if (type === "recent") return K.Variable;
  return K.Keyword;
}

export default function SqlEditor({ value, onChange, onRun, onRunScript, editorRef, schema, history, theme, errorLine, errorMessage }) {
  const [monacoInstance, setMonacoInstance] = useState(null);
  const innerEditorRef = useRef(null);
  const providerRef = useRef(null);
  const onRunRef = useRef(onRun);
  onRunRef.current = onRun;
  const onRunScriptRef = useRef(onRunScript);
  onRunScriptRef.current = onRunScript;

  const schemaSig = useMemo(
    () => JSON.stringify((schema || []).map(t => [t.name, (t.columns || []).map(c => c.name)])),
    [schema]
  );
  const base = useMemo(() => buildBaseCandidates(schema || []), [schemaSig]);
  const baseRef = useRef(base);
  baseRef.current = base;

  const histSig = useMemo(
    () => JSON.stringify((history || []).slice(0, 20).map(e => (typeof e === "string" ? e : e.sql || ""))),
    [history]
  );
  const recent = useMemo(() => getRecentIdentifiers(history || []), [histSig]);
  const recentRef = useRef(recent);
  recentRef.current = recent;
  const schemaForCtx = useRef(schema);
  schemaForCtx.current = schema;

  const handleBeforeMount = (m) => {
    definePlaygroundThemes(m);
    setMonacoInstance(m);
  };

  // Register (and re-register on candidate change) once monaco is ready.
  useEffect(() => {
    if (!monacoInstance) return;
    if (providerRef.current) providerRef.current.dispose();
    providerRef.current = monacoInstance.languages.registerCompletionItemProvider("sql", {
      triggerCharacters: TRIGGER_CHARS,
      provideCompletionItems(model, position) {
        const fullText = model.getValue();
        const offset = model.getOffsetAt(position);
        const textBefore = fullText.slice(0, offset);
        const word = model.getWordUntilPosition(position);
        const prefix = word.word || "";
        const ctx = getCompletionContext(textBefore, fullText, schemaForCtx.current || []);
        const ranked = rankCompletions({
          prefix,
          context: ctx,
          base: baseRef.current,
          recentList: recentRef.current,
          fullText,
          textBefore,
        });
        const range = new monacoInstance.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        const suggestions = ranked.slice(0, 40).map(item => {
          const isSnippet = item.type === "snippet";
          const isFunction = item.type === "function";
          return {
            label: item.label,
            kind: kindFor(monacoInstance, item.type),
            detail: item.detail || "",
            filterText: item.label,
            sortText: item.sortText,
            range,
            insertText: isSnippet ? item.insert : isFunction ? `${item.label}(${'${1:*}'})` : item.label,
            insertTextRules: (isSnippet || isFunction)
              ? monacoInstance.languages.CompletionItemInsertTextRule.InsertAsSnippet
              : undefined,
          };
        });
        return { suggestions };
      },
    });
    return () => { if (providerRef.current) providerRef.current.dispose(); };
  }, [monacoInstance, schemaSig, histSig]);

  useEffect(() => {
    if (monacoInstance) {
      monacoInstance.editor.setTheme(theme === "dark" ? "sql-playground-dark" : "sql-playground-light");
    }
  }, [monacoInstance, theme]);

  useEffect(() => {
    const editor = innerEditorRef.current;
    if (!editor || !monacoInstance) return;
    const model = editor.getModel();
    if (!model) return;
    if (errorLine) {
      monacoInstance.editor.setModelMarkers(model, "sql", [{
        severity: monacoInstance.MarkerSeverity.Error,
        message: errorMessage || "Query error",
        startLineNumber: errorLine, startColumn: 1,
        endLineNumber: errorLine, endColumn: model.getLineMaxColumn(errorLine),
      }]);
    } else {
      monacoInstance.editor.setModelMarkers(model, "sql", []);
    }
  }, [monacoInstance, errorLine, errorMessage, value]);

  const handleMount = (editor, m) => {
    innerEditorRef.current = editor;
    if (editorRef) editorRef.current = editor;
    editor.addCommand(m.KeyMod.CtrlCmd | m.KeyCode.Enter, () => onRunRef.current());
    editor.addCommand(m.KeyMod.CtrlCmd | m.KeyMod.Shift | m.KeyCode.Enter, () => onRunScriptRef.current?.());
    m.editor.setTheme(theme === "dark" ? "sql-playground-dark" : "sql-playground-light");
  };

  useEffect(() => () => { if (editorRef) editorRef.current = null; }, [editorRef]);

  return (
    <div style={{ border: "1px solid var(--line)", borderRadius: 3, overflow: "hidden", background: "var(--bg-inset)" }}>
      <Editor
        height={200}
        language="sql"
        value={value}
        theme={theme === "dark" ? "sql-playground-dark" : "sql-playground-light"}
        beforeMount={handleBeforeMount}
        onMount={handleMount}
        onChange={(v) => onChange(v ?? "")}
        options={{
          fontFamily: "'IBM Plex Mono', ui-monospace, monospace",
          fontSize: 13,
          lineHeight: 21,
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
          automaticLayout: true,
          padding: { top: 10 },
          renderLineHighlight: "line",
          cursorBlinking: "smooth",
          quickSuggestions: { other: true, comments: false, strings: false },
          suggestOnTriggerCharacters: true,
          acceptSuggestionOnCommitCharacter: true,
          tabCompletion: "on",
          wordBasedSuggestions: "off",
          suggest: { showWords: false },
          scrollbar: { verticalScrollbarSize: 8, horizontalScrollbarSize: 8 },
        }}
      />
    </div>
  );
}
