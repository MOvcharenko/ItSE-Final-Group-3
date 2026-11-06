import { chosenTermId, getJson, rememberTerm } from './api.js';
import { defaultTerm } from './calendar.js';
import { today } from './dates.js';
import { banner, el } from './dom.js';
import { meetingLabel } from './timetable.js';

const sectionCount = (count) => (count === 1 ? '1 section' : `${count} sections`);

/** "2 hours", "2 h 5 min" or "45 min" a week. */
function contactTime(minutes) {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (!hours) return `${rest} min`;
  if (!rest) return hours === 1 ? '1 hour' : `${hours} hours`;
  return `${hours} h ${rest} min`;
}

function scheduleRow(section, me) {
  const others = section.instructors.filter((person) => person.id !== me).map((person) => person.name);
  return el('tr', {},
    // One block per cell, so the phone card layout keeps code and title together on the right.
    el('td', { 'data-label': 'Course' }, el('div', {},
      el('div', {}, el('strong', {}, `${section.courseCode} · ${section.sectionNumber}`)),
      el('div', { class: 'muted' }, section.courseTitle))),
    el('td', { 'data-label': 'Meets' },
      el('div', {}, section.meetings.map((meeting) => el('div', { class: 'nowrap' }, meetingLabel(meeting))))),
    el('td', { 'data-label': 'Room' }, section.roomName
      ? el('div', {}, el('div', {}, section.roomName), el('div', { class: 'muted' }, section.building))
      : el('span', { class: 'tag cancelled' }, 'No room yet')),
    el('td', { 'data-label': 'Teaching with' }, others.join(', ') || '—'));
}

function schedulePanel(teaching, me) {
  const { term, sections } = teaching;
  const head = el('div', { class: 'panel-head' },
    el('h2', {}, 'Teaching schedule'),
    el('span', { class: 'meta' }, `${term.name} · ${sectionCount(sections.length)}`));
  if (!sections.length) {
    return el('section', { class: 'panel' }, head, el('div', { class: 'empty' },
      el('strong', {}, `You’re not teaching any sections in ${term.name}`),
      'Sections a Scheduler assigns to you will appear here.'));
  }
  return el('section', { class: 'panel' }, head,
    el('table', { class: 'cards' },
      el('thead', {}, el('tr', {}, ['Course', 'Meets', 'Room', 'Teaching with'].map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, sections.map((section) => scheduleRow(section, me)))));
}

function workloadPanel({ sections, workload }) {
  const byCourse = new Map();
  sections.forEach((section) => {
    const row = byCourse.get(section.courseCode) ?? { title: section.courseTitle, sections: 0, credits: 0 };
    row.sections += 1;
    row.credits += section.creditHours;
    byCourse.set(section.courseCode, row);
  });
  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'Workload')),
    sections.length > 0 && el('table', {},
      el('thead', {}, el('tr', {}, ['Course', 'Sections', 'Credit hrs'].map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, [...byCourse].map(([code, row]) => el('tr', {},
        el('td', {}, code), el('td', {}, String(row.sections)), el('td', {}, String(row.credits)))))),
    el('dl', { class: 'totals' },
      el('div', {}, el('dt', {}, 'Sections'), el('dd', {}, String(workload.sections))),
      el('div', {}, el('dt', {}, 'Credit hours'), el('dd', {}, String(workload.creditHours))),
      el('div', {}, el('dt', {}, 'Weekly hours'), el('dd', {}, contactTime(workload.weeklyContactMinutes)))),
    el('p', { class: 'hint panel-body' }, 'Weekly hours count time in class each week. One-time meetings and exams are listed in your schedule.'));
}

function availabilityPanel() {
  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'Availability and equipment needs')),
    el('div', { class: 'panel-body' },
      el('p', {}, 'Submitting the times you can and can’t teach, and the equipment your courses need, isn’t available yet. Until it is, tell your Scheduler directly.'),
      el('p', { class: 'hint' }, 'For a one-off room or item, ', el('a', { href: '#bookings' }, 'make a booking request'), '.')));
}

/** My Teaching: the signed-in instructor's own sections and workload. The API only ever returns the caller's own. */
export function teachingScreen(user, setTopbar) {
  const messages = el('div', { class: 'messages' });
  const body = el('div', { class: 'teaching', 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading your teaching…'));
  const termSelect = el('select', { 'aria-label': 'Term', onchange: () => selectTerm(termSelect.value) });
  const termTag = el('span', { class: 'tag' });
  let terms = [];
  let requestNumber = 0;

  function showTerm(term) {
    const draft = term.status === 'DRAFT';
    termTag.className = draft ? 'tag draft' : 'tag published';
    termTag.textContent = draft ? 'Draft' : 'Published';
    termSelect.value = term.id;
    setTopbar([termSelect, termTag], [el('a', { class: 'btn', href: '#calendar' }, 'Open My Calendar')]);
  }

  function selectTerm(id) {
    rememberTerm(id);
    showTerm(terms.find((term) => term.id === id));
    load(id);
  }

  async function load(termId) {
    const thisRequest = ++requestNumber;
    body.setAttribute('aria-busy', 'true');
    try {
      const teaching = await getJson(`/api/teaching?termId=${encodeURIComponent(termId)}`);
      if (thisRequest !== requestNumber) return;
      messages.replaceChildren();
      body.replaceChildren(
        el('div', { class: 'two-col' }, schedulePanel(teaching, user.id), workloadPanel(teaching)),
        availabilityPanel());
    } catch (error) {
      if (thisRequest !== requestNumber) return;
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => load(termId)));
    } finally {
      if (thisRequest === requestNumber) body.removeAttribute('aria-busy');
    }
  }

  async function start() {
    try {
      terms = await getJson('/api/terms');
      // Instructors can list Drafts, but only see their teaching once a term is Published.
      if (user.role !== 'SCHEDULER') terms = terms.filter((term) => term.status === 'PUBLISHED');
    } catch (error) {
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => { messages.replaceChildren(); start(); }));
      return;
    }
    if (!terms.length) {
      body.replaceChildren(el('section', { class: 'panel' }, el('div', { class: 'empty' },
        el('strong', {}, 'No terms to show yet'),
        'Your teaching schedule will appear here once a term is published.')));
      return;
    }
    termSelect.replaceChildren(...[...terms].sort((a, b) => b.startDate.localeCompare(a.startDate))
      .map((term) => el('option', { value: term.id }, term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name)));
    const term = terms.find((item) => item.id === chosenTermId()) ?? defaultTerm(terms, today());
    showTerm(term);
    load(term.id);
  }

  start();
  return el('div', { class: 'content' }, messages, body);
}
