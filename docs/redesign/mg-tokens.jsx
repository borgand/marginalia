// mg-tokens.jsx — Marginalia design tokens, named by ROLE (never by appearance).
// Each token carries the verbatim IDE dereference (`api`) the implementing agent
// binds to, plus a Light and a Dark render value for the mock. One id => one IDE
// lookup => both modes. The api field is the source of truth at runtime; the
// hexes only render this mock and serve as the implementer's JBColor fallback.
//
// Exposes to window:
//   MG_TOKENS, MG_TOKEN_ORDER, MG_USAGES   — the manifest data
//   makeMGTheme(mode)                      — resolves every role to a hex for `mode`
//   mgStatus(theme, status)                — {label,color,soft} for a comment status
//   MGThemeProvider / useMG                — React context carrying the resolved theme
//   MG_SYNTAX                              — editor syntax colors (from the editor
//                                            color scheme, NOT MarginaliaColors)

// ── the manifest: role id -> { api, key?, light, dark, role, group } ──────────
const MG_TOKENS = {
  // The core six
  'surface.toolWindow': {
    api: 'UIUtil.getPanelBackground()',
    light: '#F7F8FA', dark: '#2B2D30',
    role: 'Tool window & its rows', group: 'core',
  },
  'surface.editor': {
    api: 'EditorColorsManager.getInstance().globalScheme.defaultBackground',
    light: '#FFFFFF', dark: '#1E1F22',
    role: 'Editor + overlays drawn on it', group: 'core',
  },
  'accent': {
    api: 'JBUI.CurrentTheme.Link.Foreground.ENABLED', key: 'Link.activeForeground',
    light: '#3574F0', dark: '#548AF7',
    role: 'Links, active icons, delivered', group: 'core',
  },
  'status.pending': {
    api: 'JBUI.CurrentTheme.Label.warningForeground()', key: 'Label.warningForeground',
    light: '#A46704', dark: '#BA9752',
    role: 'Queued / pending text & marks', group: 'core',
  },
  'status.resolved': {
    api: 'JBColor.namedColor("Banner.successBackground", JBColor(0x208A3C, 0x5FAD65))',
    light: '#208A3C', dark: '#5FAD65',
    role: 'Resolved / connected / success', group: 'core',
  },
  'text.muted': {
    api: 'JBColor.namedColor("Label.infoForeground", JBColor(0x818594, 0x6F737A))',
    key: 'Label.infoForeground',
    light: '#818594', dark: '#6F737A',
    role: 'Secondary / metadata text', group: 'core',
  },
  // Companions needed before the mock is complete
  'text.primary': {
    api: 'UIUtil.getLabelForeground()', key: 'Label.foreground',
    light: '#1E1F22', dark: '#DFE1E5',
    role: 'Body text', group: 'companion',
  },
  'accent.button': {
    api: 'JBUI.CurrentTheme.Button.defaultButtonColorStart()',
    light: '#3574F0', dark: '#3573F0',
    role: 'Filled primary button bg', group: 'companion',
  },
  'text.onAccent': {
    api: 'JBColor.namedColor("Button.default.foreground", JBColor(0xFFFFFF, 0xFFFFFF))',
    light: '#FFFFFF', dark: '#FFFFFF',
    role: 'Label on the filled button', group: 'companion',
  },
  'border': {
    api: 'JBColor.namedColor("Component.borderColor", JBColor(0xEBECF0, 0x1E1F22))',
    light: '#EBECF0', dark: '#393B40',
    role: 'Separators & card borders', group: 'companion',
  },
  'selection.bg': {
    api: 'JBColor.namedColor("List.selectionBackground", JBColor(0xD5E4FF, 0x2E436E))',
    light: '#D5E4FF', dark: '#2E436E',
    role: 'Active / selected row', group: 'companion',
  },
  'status.conflict': {
    api: 'JBColor.namedColor("Component.errorFocusColor", JBColor(0xE53E4D, 0xF75464))',
    key: 'Component.errorFocusColor',
    light: '#E53E4D', dark: '#F75464',
    role: 'Failed / delivery error', group: 'companion',
  },
};

const MG_TOKEN_ORDER = [
  'surface.toolWindow', 'surface.editor', 'accent', 'status.pending', 'status.resolved', 'text.muted',
  'text.primary', 'accent.button', 'text.onAccent', 'border', 'selection.bg', 'status.conflict',
];

// Every distinct colored element in the mock -> the token (and modifier) it maps to.
const MG_USAGES = [
  { element: 'Tool-window & comment-card bg', token: 'surface.toolWindow' },
  { element: 'Group-header / footer strip', token: 'surface.toolWindow shaded' },
  { element: 'Editor canvas, inputs, anchor box', token: 'surface.editor' },
  { element: 'Floating add-comment toolbar/popup bg', token: 'surface.editor' },
  { element: 'Body / comment text', token: 'text.primary' },
  { element: 'File name, timestamps, MCP chip', token: 'text.muted' },
  { element: 'Hairline separators & card borders', token: 'border' },
  { element: 'Queued status dot / pill / "to send"', token: 'status.pending' },
  { element: 'Queued range highlight (gutter + line)', token: 'status.pending @ 12%' },
  { element: 'Delivered status, links, active icons', token: 'accent' },
  { element: 'Submit-review / Add-comment button bg', token: 'accent.button' },
  { element: 'Label on the filled button', token: 'text.onAccent' },
  { element: 'Resolved checkmark / pill', token: 'status.resolved' },
  { element: '"Connected" chip + footer agent dot', token: 'status.resolved' },
  { element: 'Failed status pill + retry', token: 'status.conflict' },
  { element: 'Selected / hovered comment row', token: 'selection.bg' },
];

// ── editor syntax: comes from the active EditorColorsScheme, not the UI theme.
// Kept separate so nobody mistakes it for a MarginaliaColors token.
const MG_SYNTAX = {
  dark:  { text: '#BCBEC4', head: '#C77DBB', kw: '#CF8E6D', str: '#6AAB73', link: '#548AF7', punc: '#7A7E87', lineNr: '#4E5157' },
  light: { text: '#2B2D30', head: '#871094', kw: '#0033B3', str: '#067D17', link: '#3574F0', punc: '#9AA0A6', lineNr: '#A8ADB5' },
};

// ── color helpers (deterministic functions of a token, never new raw hex) ─────
function _toRgb(hex) {
  let h = hex.replace('#', '');
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}
function _toHex(r, g, b) {
  const c = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0');
  return '#' + c(r) + c(g) + c(b);
}
function withAlpha(hex, a) { const [r, g, b] = _toRgb(hex); return `rgba(${r},${g},${b},${a})`; }
function mix(hexA, hexB, t) {
  const a = _toRgb(hexA), b = _toRgb(hexB);
  return _toHex(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
}
// amt > 0 lightens toward white, amt < 0 darkens toward black.
function shade(hex, amt) { return mix(hex, amt >= 0 ? '#FFFFFF' : '#000000', Math.abs(amt)); }

// ── resolve every role to a concrete value for one mode ───────────────────────
function makeMGTheme(mode) {
  const pick = (id) => MG_TOKENS[id][mode];
  const dark = mode === 'dark';
  const surface = pick('surface.toolWindow');
  const pending = pick('status.pending');
  const accent = pick('accent');
  const textPrimary = pick('text.primary');
  const textMuted = pick('text.muted');

  const t = {
    mode, dark, withAlpha, shade, mix,
    // resolved role tokens
    surfaceToolWindow: surface,
    surfaceEditor: pick('surface.editor'),
    accent,
    accentButton: pick('accent.button'),
    textOnAccent: pick('text.onAccent'),
    textPrimary,
    textMuted,
    border: pick('border'),
    selectionBg: pick('selection.bg'),
    status: { queued: pending, delivered: accent, resolved: pick('status.resolved'), failed: pick('status.conflict') },
    syntax: MG_SYNTAX[mode],
    // a secondary text weight between primary and muted (derived, not a token)
    dim: mix(textPrimary, textMuted, 0.45),
    // recessed / raised / hovered surfaces, derived from surface.toolWindow
    raised: shade(surface, dark ? -0.045 : -0.022),
    rowHover: shade(surface, dark ? 0.06 : -0.028),
    track: shade(surface, dark ? -0.10 : -0.07),
    chipBg: shade(surface, dark ? -0.07 : -0.04),
  };
  // translucent pending highlight = status.pending @ 12% / @ 6% (rule 6)
  t.queuedTint = withAlpha(pending, 0.12);
  t.queuedTintSoft = withAlpha(pending, dark ? 0.06 : 0.05);
  return t;
}

const MG_STATUS_LABEL = { queued: 'Queued', delivered: 'Delivered', resolved: 'Resolved', failed: 'Failed' };
function mgStatus(t, status) {
  const color = t.status[status];
  return { label: MG_STATUS_LABEL[status], color, soft: withAlpha(color, t.dark ? 0.16 : 0.13) };
}

// ── React context carrying the resolved theme ────────────────────────────────
const MGThemeContext = React.createContext(makeMGTheme('dark'));
function MGThemeProvider({ mode, children }) {
  const value = React.useMemo(() => makeMGTheme(mode), [mode]);
  return React.createElement(MGThemeContext.Provider, { value }, children);
}
function useMG() { return React.useContext(MGThemeContext); }

Object.assign(window, {
  MG_TOKENS, MG_TOKEN_ORDER, MG_USAGES, MG_SYNTAX,
  makeMGTheme, mgStatus, MGThemeProvider, useMG, mgColor: { withAlpha, mix, shade },
});
