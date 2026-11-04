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

/** A red message with an optional action, such as "Try again". */
export function banner(message, actionLabel, onAction) {
  return el('div', { class: 'banner', role: 'alert' },
    el('span', {}, message),
    actionLabel && el('button', { class: 'btn small', type: 'button', onclick: onAction }, actionLabel));
}
