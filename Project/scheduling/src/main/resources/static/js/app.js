import { getAuthConfig, signIn, signOutOfCorePlatform, startOidcSignIn } from './auth.js';
import { bookingsScreen } from './bookings.js';
import { calendarScreen } from './calendar.js';
import { catalogScreen } from './catalog.js';
import { conflictsScreen } from './conflicts.js';
import { banner, el, initials } from './dom.js';
import { latestOnly, setSessionEndedHandler, Superseded } from './http.js';
import { insightsScreen } from './insights.js';
import { NOTICES_CHANGED, UNREAD_COUNTED } from './notices.js';
import { api } from './scheduling-api.js';
import { currentUser } from './session.js';
import { appShell, landing, roleLabel } from './shell.js';
import { teachingScreen } from './teaching.js';
import { timetableScreen } from './timetable.js';

const UNREAD_REFRESH_MS = 60000;

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
const fetchNotices = latestOnly(() => api.notices());

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
  try {
    const notices = await fetchNotices();
    const count = notices.filter((notice) => !notice.read).length;
    const changed = count !== unreadCount;
    showUnread(count);
    if (changed) document.dispatchEvent(new Event(NOTICES_CHANGED));
  } catch (error) {
    if (error instanceof Superseded) return; // the panel has reported something newer
    // otherwise keep the last count
  }
}

/**
 * switchAccount: the signed-in account can't be used here, so signing in first ends its Core Platform session
 * (otherwise the Core Platform would sign the same account straight back in).
 */
async function showSignIn(message, { switchAccount = false } = {}) {
  unreadBadge = null;
  unreadCount = 0;
  fetchNotices.drop();
  const messages = el('div', {}, message && banner(message));

  let config;
  try {
    config = await getAuthConfig();
  } catch (error) {
    document.title = 'Sign in · Academic Scheduling';
    root.replaceChildren(landing(null,
      banner(error.message || 'Could not load sign-in configuration.', 'Try again', () => showSignIn(message, { switchAccount }))));
    return;
  }

  // Nothing to choose here, so go straight to the sign-in form. With a message to read, wait for a click instead.
  if (config.mode === 'oidc' && !message) {
    document.title = 'Signing in · Academic Scheduling';
    root.replaceChildren(landing('Opening sign-in…'));
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
  root.replaceChildren(landing(null, messages, body, el('p', { class: 'landing-note' }, note)));
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

  unreadBadge = el('span', { class: 'nav-badge', hidden: true });
  showUnread(unreadCount);

  const shell = appShell({
    user,
    screens,
    current: screen,
    badges: { calendar: unreadBadge },
    accountUri: config?.accountUri,
    // With a message, the sign-in page waits for a click instead of signing straight back in.
    onSwitchAccount: () => {
      if (!signOutOfCorePlatform(config)) showSignIn('You have signed out. Sign in to choose an account.');
    },
  });
  document.title = `${screen.title(user.role)} · Academic Scheduling`;
  root.replaceChildren(shell.element);
  shell.content.append(screen.render(user, shell.setTopbar));
  refreshUnread();
}

setSessionEndedHandler(() => showSignIn('Your session has ended. Please sign in again.'));
window.addEventListener('hashchange', showApp);
document.addEventListener(UNREAD_COUNTED, (event) => {
  fetchNotices.drop();
  showUnread(event.detail);
});
// Not while a form is open: an expired session would return to sign-in and lose what was typed.
setInterval(() => {
  if (!document.hidden && !document.querySelector('dialog[open]')) refreshUnread();
}, UNREAD_REFRESH_MS);
showApp();
