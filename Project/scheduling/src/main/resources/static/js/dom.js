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
 * searchText(row): the words a row can be found by, for filterRows.
 */
export function dataTable(columns, rows, { cards = false, searchText } = {}) {
  const shown = columns.filter(Boolean);
  return el('div', { class: 'scroll-x' }, el('table', { class: cards ? 'cards' : null },
    el('thead', {}, el('tr', {}, shown.map((column) => el('th', { scope: 'col' }, column.label)))),
    el('tbody', {}, rows.map((row) => el('tr', { 'data-search': searchText ? searchText(row).toLowerCase() : null },
      shown.map((column) => el('td', { class: column.class, 'data-label': column.label || null }, column.cell(row))))))));
}

/**
 * A search box that hides the rows of a dataTable (made with searchText) that don't contain every typed word.
 * onCount(shown, total) reports how many are left, for a "12 of 300" label.
 */
export function filterRows(table, { placeholder, label, onCount = () => {} }) {
  const box = el('input', {
    type: 'search',
    class: 'find',
    placeholder,
    'aria-label': label ?? placeholder,
    oninput: () => {
      const words = box.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
      const rows = [...table.querySelectorAll('tbody tr')];
      let shown = 0;
      rows.forEach((row) => {
        row.hidden = words.some((word) => !row.dataset.search.includes(word));
        if (!row.hidden) shown++;
      });
      onCount(shown, rows.length);
    },
  });
  return box;
}

/**
 * A button that opens a short list of actions, for actions used now and then. items: [{ label, onClick, danger? }];
 * falsy items are left out. Choosing one, Esc or a click elsewhere closes the list.
 */
export function actionMenu(label, items) {
  const menu = el('details', { class: 'menu' });
  const close = () => { menu.open = false; };
  menu.append(
    el('summary', { class: 'btn' }, label, el('span', { class: 'caret', 'aria-hidden': 'true' }, '▾')),
    el('div', { class: 'menu-list' }, items.filter(Boolean).map((item) => el('button', {
      class: item.danger ? 'danger' : null,
      type: 'button',
      onclick: () => { close(); item.onClick(); },
    }, item.label))));
  const outside = (event) => { if (!menu.contains(event.target)) close(); };
  const escape = (event) => {
    if (event.key !== 'Escape') return;
    close();
    menu.querySelector('summary').focus();
  };
  menu.addEventListener('toggle', () => {
    const method = menu.open ? 'addEventListener' : 'removeEventListener';
    document[method]('click', outside);
    document[method]('keydown', escape);
  });
  return menu;
}

/**
 * A panel that folds away to its heading line. head: the heading and any summary text (no buttons: the whole line is
 * the toggle). onToggle(open) runs when it is opened or closed.
 */
export function foldingPanel(head, body, { open = true, onToggle } = {}) {
  const panel = el('details', { class: 'panel fold', open },
    el('summary', { class: 'panel-head' }, head), body);
  if (onToggle) panel.addEventListener('toggle', () => onToggle(panel.open));
  return panel;
}
