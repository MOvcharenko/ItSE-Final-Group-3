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
 * stays there to be corrected. The dialog lives inside #app, so returning to sign-in removes it too.
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
          await onSubmit(new FormData(event.target));
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
