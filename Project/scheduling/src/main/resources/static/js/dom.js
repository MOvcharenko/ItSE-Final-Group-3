/**
 * Builds an element. Children become text nodes, never HTML, so names and messages from the API cannot inject markup.
 * Props starting with "on" are listeners, and null or false props are skipped.
 */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  node.append(...children.flat().filter((child) => child != null && child !== false));
  return node;
}

export function initials(name) {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0].toUpperCase()).join('');
}

/** A red message with an optional action, such as "Try again". Only for something that went wrong. */
export function banner(message, actionLabel, onAction) {
  return el('div', { class: 'banner', role: 'alert' },
    el('span', {}, message),
    actionLabel && el('button', { class: 'btn small', type: 'button', onclick: onAction }, actionLabel));
}

/**
 * A data table described by its columns: [{ label, cell(row), class? }]. Every table gets column headers with a scope,
 * cells labelled with their column (cards on phones show the label), and sideways scrolling when it is too wide.
 * cards: shown as one card per row on a phone. Falsy columns are left out, so a column can depend on the role.
 */
export function dataTable(columns, rows, { cards = false } = {}) {
  const shown = columns.filter(Boolean);
  return el('div', { class: 'scroll-x' }, el('table', { class: cards ? 'cards' : null },
    el('thead', {}, el('tr', {}, shown.map((column) => el('th', { scope: 'col' }, column.label)))),
    el('tbody', {}, rows.map((row) => el('tr', {}, shown.map((column) =>
      el('td', { class: column.class, 'data-label': column.label || null }, column.cell(row))))))));
}
