import { banner, el } from './dom.js';

let nextId = 1;

/** A labelled control. */
export function field(label, control, hint) {
  const id = control.id || `field-${nextId++}`;
  control.id = id;
  return el('div', { class: 'field' },
    el('label', { for: id }, label),
    control,
    hint && el('span', { class: 'hint' }, hint));
}

export function input(name, props = {}) {
  return el('input', { name, type: 'text', ...props });
}

/** options is a list of [value, label]. */
export function select(name, options, selected, props = {}) {
  return el('select', { name, ...props }, options.map(([value, label]) =>
    el('option', { value, selected: value === selected }, label)));
}

/**
 * Checkboxes for picking several from a long list, such as instructors: the ones already chosen come first, a box
 * filters the rest by name, and the list scrolls in a fixed height so the form stays short. options: [[value, label]].
 * Returns the fieldset; its checkboxes are named name, so FormData.getAll(name) gives the choice. Hidden rows still
 * submit, so filtering never drops a choice.
 */
export function checklist(legend, name, options, chosen = new Set()) {
  const count = el('span', { class: 'hint' });
  const boxes = [];
  const sorted = [...options].sort((a, b) => Number(chosen.has(b[0])) - Number(chosen.has(a[0])) || a[1].localeCompare(b[1]));
  const items = sorted.map(([value, label]) => {
    const box = el('input', { type: 'checkbox', name, value, checked: chosen.has(value), onchange: showCount });
    boxes.push(box);
    return el('label', { class: 'check', 'data-search': label.toLowerCase() }, box, label);
  });
  const none = el('p', { class: 'hint', hidden: true }, 'No one matches.');
  const find = options.length > 8 && el('input', {
    type: 'search',
    class: 'find',
    placeholder: `Find by name`,
    'aria-label': `Find in ${legend.toLowerCase()}`,
    oninput: () => {
      const text = find.value.trim().toLowerCase();
      items.forEach((item) => { item.hidden = Boolean(text) && !item.dataset.search.includes(text); });
      none.hidden = items.some((item) => !item.hidden);
    },
  });
  function showCount() {
    const picked = boxes.filter((box) => box.checked).length;
    count.textContent = picked ? `${picked} chosen` : 'None chosen';
  }
  showCount();
  return el('fieldset', { class: 'group' },
    el('legend', {}, legend),
    el('div', { class: 'checklist-head' }, find, count),
    el('div', { class: options.length > 8 ? 'checklist scrolling' : 'checklist' }, items, none));
}

/** Opens a window listing items to choose from, [{ label, detail, onClick }]. Choosing one closes it first. */
export function openList(title, items) {
  const titleId = `dialog-title-${nextId++}`;
  const dialog = el('dialog', { class: 'dialog', 'aria-labelledby': titleId, onclose: () => dialog.remove() },
    el('h2', { id: titleId }, title),
    el('ul', { class: 'pick-list' }, items.map((item) => el('li', {},
      el('button', {
        class: 'pick',
        type: 'button',
        onclick: () => {
          dialog.close();
          item.onClick();
        },
      }, el('strong', {}, item.label), item.detail && el('span', { class: 'muted' }, item.detail))))),
    el('form', { class: 'form-actions', method: 'dialog' },
      el('button', { class: 'btn', type: 'submit' }, 'Close')));
  document.getElementById('app').append(dialog);
  dialog.showModal();
}

/** Opens a read-only window of [label, value] pairs. The browser closes it on Close or Esc and returns focus. */
export function openDetails(title, items) {
  const titleId = `dialog-title-${nextId++}`;
  const dialog = el('dialog', { class: 'dialog', 'aria-labelledby': titleId, onclose: () => dialog.remove() },
    el('h2', { id: titleId }, title),
    el('dl', { class: 'details' }, items.flatMap(([label, value]) => [el('dt', {}, label), el('dd', {}, value)])),
    el('form', { class: 'form-actions', method: 'dialog' },
      el('button', { class: 'btn primary', type: 'submit' }, 'Close')));
  document.getElementById('app').append(dialog);
  dialog.showModal();
}

/**
 * Opens a modal form. onSubmit gets the FormData; if it throws, the message appears in the form and what was typed
 * stays there to be corrected. If it returns false (the user backed out of a confirmation), the form stays open as is.
 * The dialog lives inside #app, so returning to sign-in removes it too.
 */
export function openForm({ title, note, submitLabel, closeLabel = 'Cancel', body, onSubmit }) {
  const messages = el('div', { class: 'messages' });
  const submit = el('button', { class: 'btn primary', type: 'submit' }, submitLabel);
  const titleId = `dialog-title-${nextId++}`;
  const dialog = el('dialog', { class: 'dialog', 'aria-labelledby': titleId, onclose: () => dialog.remove() },
    el('form', {
      onsubmit: async (event) => {
        event.preventDefault();
        submit.disabled = true;
        try {
          if (await onSubmit(new FormData(event.target)) === false) return;
          dialog.close();
        } catch (error) {
          messages.replaceChildren(banner(error.message));
          dialog.scrollTop = 0;
        } finally {
          submit.disabled = false;
        }
      },
    },
      el('h2', { id: titleId }, title),
      note && el('p', { class: 'muted' }, note),
      messages,
      el('div', { class: 'form-body' }, body),
      el('div', { class: 'form-actions' },
        el('button', { class: 'btn', type: 'button', onclick: () => dialog.close() }, closeLabel),
        submit)));
  document.getElementById('app').append(dialog);
  dialog.showModal();
}
