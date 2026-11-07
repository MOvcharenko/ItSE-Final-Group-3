import {
  currentUser, getAuthConfig, getJson, setSessionEndedHandler, signIn, signOutOfCorePlatform, startOidcSignIn,
} from './api.js';
import { bookingsScreen } from './bookings.js';
import { calendarScreen } from './calendar.js';
import { catalogScreen } from './catalog.js';
import { conflictsScreen } from './conflicts.js';
import { banner, el, initials } from './dom.js';
import { insightsScreen } from './insights.js';
import { NOTICES_CHANGED, UNREAD_COUNTED } from './notices.js';
import { teachingScreen } from './teaching.js';
import { timetableScreen } from './timetable.js';

const UNREAD_REFRESH_MS = 60000;

/** "SCHEDULER" → "Scheduler". The role comes from the token or /auth/config, never from a list here. */
const roleLabel = (role) => role.charAt(0) + role.slice(1).toLowerCase();

// What each role can do, for the dev-account picker. The accounts themselves come from /auth/config.
const ROLE_BLURBS = {
  SCHEDULER: 'Builds timetables and manages subjects, rooms and equipment.',
  INSTRUCTOR: 'Sees the sections they teach and requests rooms and equipment.',
  STUDENT: 'Sees the classes and exams they are enrolled in.',
};

// Add a screen here and it appears in the sidebar for the roles listed. The first one a role can use opens by default.
const SCREENS = [
  { key: 'timetable', roles: ['SCHEDULER'], title: () => 'Timetable', render: timetableScreen },
  { key: 'conflicts', roles: ['SCHEDULER'], title: () => 'Conflicts', render: conflictsScreen },
  { key: 'insights', roles: ['SCHEDULER'], title: () => 'Insights', render: insightsScreen },
  { key: 'catalog', roles: ['SCHEDULER'], title: () => 'Catalog', render: catalogScreen },
  // An instructor's primary screen, so it comes first and opens by default for them.
  { key: 'teaching', roles: ['INSTRUCTOR'], title: () => 'My Teaching', render: teachingScreen },
  {
    key: 'calendar',
    roles: ['SCHEDULER', 'INSTRUCTOR', 'STUDENT'],
    title: (role) => (role === 'SCHEDULER' ? 'Calendar' : 'My Calendar'),
    render: calendarScreen,
  },
  { key: 'bookings', roles: ['SCHEDULER', 'INSTRUCTOR'], title: () => 'Bookings', render: bookingsScreen },
];

const root = document.getElementById('app');

// Notices are read on the calendar, so its sidebar link carries the unread count on every screen.
let unreadBadge = null;
let unreadCount = 0;
let unreadRequest = 0;

function showUnread(count) {
  unreadCount = count;
  if (!unreadBadge) return;
  unreadBadge.hidden = count === 0;
  unreadBadge.replaceChildren(count > 99 ? '99+' : String(count),
    el('span', { class: 'sr-only' }, count === 1 ? ' unread notice' : ' unread notices'));
}

/** A failed check keeps the last count, because the screen in use reports any real problem itself. */
async function refreshUnread() {
  if (!unreadBadge || !currentUser()) return;
  const thisRequest = ++unreadRequest;
  try {
    const notices = await getJson('/api/notices');
    if (thisRequest !== unreadRequest) return; // the panel has reported something newer
    const count = notices.filter((notice) => !notice.read).length;
    const changed = count !== unreadCount;
    showUnread(count);
    if (changed) document.dispatchEvent(new Event(NOTICES_CHANGED));
  } catch {
    // keep the last count
  }
}

/**
 * switchAccount: the signed-in account can't be used here, so signing in first ends its Core Platform session
 * (otherwise the Core Platform would sign the same account straight back in).
 */
async function showSignIn(message, { switchAccount = false } = {}) {
  unreadBadge = null;
  unreadCount = 0;
  unreadRequest++;
  const messages = el('div', {}, message && banner(message));

  let config;
  try {
    config = await getAuthConfig();
  } catch (error) {
    document.title = 'Sign in · Academic Scheduling';
    root.replaceChildren(el('main', { class: 'landing' },
      el('div', { class: 'brand' },
        el('h1', {}, 'Academic Scheduling'),
        el('p', {}, 'Timetables · Rooms · Equipment · Faculty')),
      banner(error.message || 'Could not load sign-in configuration.', 'Try again', () => showSignIn(message, { switchAccount }))));
    return;
  }

  // Nothing to choose here, so go straight to the sign-in form. With a message to read, wait for a click instead.
  if (config.mode === 'oidc' && !message) {
    document.title = 'Signing in · Academic Scheduling';
    root.replaceChildren(el('main', { class: 'landing' },
      el('div', { class: 'brand' },
        el('h1', {}, 'Academic Scheduling'),
        el('p', {}, 'Opening sign-in…'))));
    try {
      await startOidcSignIn(config);
      return;
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    }
  }

  const body = config.mode === 'dev'
    ? devAccountPicker(messages, config.accounts)
    : oidcSignInButton(messages, config, switchAccount);
  const note = config.mode === 'dev'
    ? 'These are test accounts for local development only. The dev profile signs in with /dev/token.'
    : 'Your role comes from your account.';

  document.title = 'Sign in · Academic Scheduling';
  root.replaceChildren(el('main', { class: 'landing' },
    el('div', { class: 'brand' },
      el('h1', {}, 'Academic Scheduling'),
      el('p', {}, 'Timetables · Rooms · Equipment · Faculty')),
    messages,
    body,
    el('p', { class: 'landing-note' }, note)));
}

function devAccountPicker(messages, accounts) {
  const cards = accounts.map((account) => el('button', {
    class: 'account',
    type: 'button',
    onclick: async () => {
      try {
        await signIn(account.id);
        showApp();
      } catch (error) {
        messages.replaceChildren(banner(error.message));
      }
    },
  },
    el('span', { class: 'avatar large' }, initials(account.name)),
    el('span', { class: 'name' }, account.name),
    el('span', { class: 'role' }, roleLabel(account.role)),
    el('span', { class: 'blurb' }, ROLE_BLURBS[account.role]),
    el('span', { class: 'go' }, `Continue as ${account.role.toLowerCase()} →`)));
  return el('div', { class: 'accounts' }, cards);
}

function oidcSignInButton(messages, config, switchAccount) {
  const button = el('button', {
    class: 'btn primary wide',
    type: 'button',
    onclick: async () => {
      // A second click would start a second sign-in and overwrite the first one's state, so the return would fail.
      button.disabled = true;
      try {
        // Its logout page returns to the app, which then opens sign-in; without one, sign-in asks for an account.
        if (switchAccount && signOutOfCorePlatform(config)) return;
        await startOidcSignIn(config);
      } catch (error) {
        button.disabled = false;
        messages.replaceChildren(banner(error.message));
      }
    },
  }, 'Sign in →');
  return el('div', { class: 'oidc-signin' }, button);
}

async function showApp() {
  const user = currentUser();
  if (!user) {
    await showSignIn();
    return;
  }
  const screens = SCREENS.filter((screen) => screen.roles.includes(user.role));
  const screen = screens.find((item) => item.key === location.hash.slice(1)) ?? screens[0];
  if (!screen) {
    // The token stays until "Sign in" is clicked: ending the Core Platform session then needs its ID token.
    await showSignIn('This account’s role is not recognized, so there is nothing to show. Please contact your administrator.',
      { switchAccount: true });
    return;
  }
  let config = null;
  try {
    config = await getAuthConfig();
  } catch {
    // only the Account link depends on it
  }

  const title = screen.title(user.role);
  const crumbExtras = el('div', { class: 'crumb' });
  const actions = el('div', { class: 'crumb' });
  const setTopbar = (extras, buttons) => {
    crumbExtras.replaceChildren(...extras);
    actions.replaceChildren(...buttons);
  };

  unreadBadge = el('span', { class: 'nav-badge', hidden: true });
  showUnread(unreadCount);

  document.title = `${title} · Academic Scheduling`;
  root.replaceChildren(el('div', { class: 'shell' },
    el('aside', { class: 'sidebar' },
      el('div', { class: 'wordmark' },
        el('span', { class: 'name' }, 'Academic Scheduling'),
        el('span', { class: 'sub' }, 'Timetables · Rooms · Equipment · Faculty')),
      el('nav', { class: 'nav', 'aria-label': 'Main' },
        screens.map((item) => el('a', {
          class: 'nav-item',
          href: `#${item.key}`,
          'aria-current': item === screen ? 'page' : null,
        }, el('span', { class: 'dot' }), item.title(user.role), item.key === 'calendar' && unreadBadge))),
      el('div', { class: 'sidebar-foot' },
        el('div', { class: 'who' },
          el('div', { class: 'avatar' }, initials(user.name)),
          el('div', { class: 'text' },
            el('span', { class: 'name' }, user.name),
            el('span', { class: 'role' }, roleLabel(user.role)))),
        // Change password, and manage accounts for administrators (when the sign-in has those pages).
        config?.accountUri && el('a', { class: 'btn quiet small wide', href: config.accountUri }, 'Account'),
        el('button', {
          class: 'btn quiet small wide',
          type: 'button',
          onclick: () => {
            // With a message, the sign-in page waits for a click instead of signing straight back in.
            if (!signOutOfCorePlatform(config)) showSignIn('You have signed out. Sign in to choose an account.');
          },
        }, 'Switch account'))),
    el('div', { class: 'main' },
      el('header', { class: 'topbar' },
        el('div', { class: 'crumb' }, el('h1', {}, title), crumbExtras),
        actions),
      el('main', {}, screen.render(user, setTopbar)))));
  refreshUnread();
}

setSessionEndedHandler(() => showSignIn('Your session has ended. Please sign in again.'));
window.addEventListener('hashchange', showApp);
document.addEventListener(UNREAD_COUNTED, (event) => {
  unreadRequest++;
  showUnread(event.detail);
});
// Not while a form is open: an expired session would return to sign-in and lose what was typed.
setInterval(() => {
  if (!document.hidden && !document.querySelector('dialog[open]')) refreshUnread();
}, UNREAD_REFRESH_MS);
showApp();