import { dateTime } from './dates.js';
import { banner, el } from './dom.js';
import { api } from './scheduling-api.js';

/** Sent with the unread count whenever the panel knows a newer one, for the sidebar. */
export const UNREAD_COUNTED = 'notices:unread-counted';
/** Sent when the sidebar finds that the count has changed, so an open panel can catch up. */
export const NOTICES_CHANGED = 'notices:changed';

/** The "Schedule notices" panel: the signed-in user's own notices, newest first. */
export function noticesPanel() {
  const meta = el('span', { class: 'meta' });
  const heading = el('h2', { tabindex: '-1' }, 'Schedule notices');
  const list = el('div', {}, el('div', { class: 'loading' }, 'Loading notices…'));
  let notices = [];

  async function load() {
    try {
      notices = await api.notices();
      render();
    } catch (error) {
      meta.textContent = '';
      list.replaceChildren(el('div', { class: 'panel-body' }, banner(error.message, 'Try again', load)));
    }
  }

  async function markRead(notice) {
    try {
      Object.assign(notice, await api.markNoticeRead(notice.id));
      render();
      heading.focus(); // the button that had focus is gone
    } catch (error) {
      list.prepend(el('div', { class: 'panel-body' }, banner(error.message)));
    }
  }

  function render() {
    const unread = notices.filter((notice) => !notice.read).length;
    document.dispatchEvent(new CustomEvent(UNREAD_COUNTED, { detail: unread }));
    meta.textContent = notices.length ? `${unread} unread` : '';
    list.replaceChildren(...(notices.length
      ? notices.map(noticeRow)
      : [el('div', { class: 'empty' }, 'No notices. Changes to your schedule will appear here.')]));
  }

  function noticeRow(notice) {
    return el('div', { class: notice.read ? 'notice read' : 'notice' },
      el('div', { class: 'dot' }),
      el('div', { class: 'body' },
        el('span', { class: 'text' }, notice.message),
        el('span', { class: 'when' }, dateTime(notice.createdAt)),
        !notice.read && el('button', { class: 'btn small', type: 'button', onclick: () => markRead(notice) }, 'Mark as read')));
  }

  const panel = el('section', { class: 'panel' }, el('div', { class: 'panel-head' }, heading, meta), list);
  const onChanged = () => {
    if (panel.isConnected) load();
    else document.removeEventListener(NOTICES_CHANGED, onChanged);
  };
  document.addEventListener(NOTICES_CHANGED, onChanged);
  load();
  return panel;
}
