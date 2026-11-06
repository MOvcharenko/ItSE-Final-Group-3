import { currentUser, getJson, setSessionEndedHandler, signIn, signOut } from './api.js';
import { bookingsScreen } from './bookings.js';
import { calendarScreen } from './calendar.js';
import { catalogScreen } from './catalog.js';
import { conflictsScreen } from './conflicts.js';
import { banner, el, initials } from './dom.js';
import { NOTICES_CHANGED, UNREAD_COUNTED } from './notices.js';
import { teachingScreen } from './teaching.js';
import { timetableScreen } from './timetable.js';

const UNREAD_REFRESH_MS = 60000;

const ROLE_LABELS = { SCHEDULER: 'Scheduler', INSTRUCTOR: 'Instructor', STUDENT: 'Student' };

const ACCOUNTS = [
  { id: 'U01', name: 'Maya Brooks', role: 'SCHEDULER', blurb: 'Builds timetables and manages subjects, rooms and equipment.' },
  { id: 'U02', name: 'L. Bianchi', role: 'INSTRUCTOR', blurb: 'Sees the sections they teach and requests rooms and equipment.' },
  { id: 'U03', name: 'Jordan Ellis', role: 'STUDENT', blurb: 'Sees the classes and exams they are enrolled in.' },
];

// Add a screen here and it appears in the sidebar for the roles listed. The first one a role can use opens by default.
const SCREENS = [
  { key: 'timetable', roles: ['SCHEDULER'], title: () => 'Timetable', render: timetableScreen },
  { key: 'conflicts', roles: ['SCHEDULER'], title: () => 'Conflicts', render: conflictsScreen },
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

function showSignIn(message) {
  unreadBadge = null;
  unreadCount = 0;
  unreadRequest++;
  const messages = el('div', {}, message && banner(message));
  const cards = ACCOUNTS.map((account) => el('button', {
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
    el('span', { class: 'role' }, ROLE_LABELS[account.role]),
    el('span', { class: 'blurb' }, account.blurb),
    el('span', { class: 'go' }, `Continue as ${ROLE_LABELS[account.role].toLowerCase()} →`)));

  document.title = 'Sign in · Academic Scheduling';
  root.replaceChildren(el('main', { class: 'landing' },
    el('div', { class: 'brand' },
      el('h1', {}, 'Academic Scheduling'),
      el('p', {}, 'Timetables · Rooms · Equipment · Faculty')),
    messages,
    el('div', { class: 'accounts' }, cards),
    el('p', { class: 'landing-note' }, 'These are test accounts for local development. Core Platform sign-in is not connected yet.')));
}

function showApp() {
  const user = currentUser();
  if (!user) {
    showSignIn();
    return;
  }
  const screens = SCREENS.filter((screen) => screen.roles.includes(user.role));
  const screen = screens.find((item) => item.key === location.hash.slice(1)) ?? screens[0];
  if (!screen) {
    signOut();
    showSignIn('This account’s role is not recognized, so there is nothing to show. Please contact your administrator.');
    return;
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
            el('span', { class: 'role' }, ROLE_LABELS[user.role]))),
        el('button', {
          class: 'btn quiet small wide',
          type: 'button',
          onclick: () => { signOut(); showSignIn(); },
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
