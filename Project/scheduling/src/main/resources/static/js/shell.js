// The page frames: the landing page around sign-in, and the app shell with its sidebar and top bar.
import { el, initials } from './dom.js';

const TAGLINE = 'Timetables · Rooms · Equipment · Faculty';

/** "SCHEDULER" → "Scheduler". The role comes from the token or /auth/config, never from a list here. */
export const roleLabel = (role) => role.charAt(0) + role.slice(1).toLowerCase();

/** The centred brand page that sign-in and its messages appear on. subtitle defaults to the tagline. */
export function landing(subtitle, ...content) {
  return el('main', { class: 'landing' },
    el('div', { class: 'brand' }, el('h1', {}, 'Academic Scheduling'), el('p', {}, subtitle ?? TAGLINE)),
    ...content);
}

/**
 * The sidebar (one link per screen, the signed-in person, Account and Switch account) around a top bar and the screen.
 * badges: extra content for a screen's link, by key. Returns the element and setTopbar(details, actions) for the screen.
 */
export function appShell({ user, screens, current, badges = {}, accountUri, onSwitchAccount }) {
  const details = el('div', { class: 'crumb' });
  const actions = el('div', { class: 'crumb' });
  const content = el('main');
  const title = current.title(user.role);

  const element = el('div', { class: 'shell' },
    el('aside', { class: 'sidebar' },
      el('div', { class: 'wordmark' },
        el('span', { class: 'name' }, 'Academic Scheduling'),
        el('span', { class: 'sub' }, TAGLINE)),
      el('nav', { class: 'nav', 'aria-label': 'Main' },
        screens.map((screen) => el('a', {
          class: 'nav-item',
          href: `#${screen.key}`,
          'aria-current': screen === current ? 'page' : null,
        }, el('span', { class: 'dot' }), screen.title(user.role), badges[screen.key]))),
      el('div', { class: 'sidebar-foot' },
        el('div', { class: 'who' },
          el('div', { class: 'avatar' }, initials(user.name)),
          el('div', { class: 'text' },
            el('span', { class: 'name' }, user.name),
            el('span', { class: 'role' }, roleLabel(user.role)))),
        // Change password, and manage accounts for administrators (when the sign-in has those pages).
        accountUri && el('a', { class: 'btn quiet small wide', href: accountUri }, 'Account'),
        el('button', { class: 'btn quiet small wide', type: 'button', onclick: onSwitchAccount }, 'Switch account'))),
    el('div', { class: 'main' },
      el('header', { class: 'topbar' },
        el('div', { class: 'crumb' }, el('h1', {}, title), details),
        actions),
      content));

  return {
    element,
    content,
    setTopbar(detailItems, actionItems) {
      details.replaceChildren(...detailItems);
      actions.replaceChildren(...actionItems);
    },
  };
}
