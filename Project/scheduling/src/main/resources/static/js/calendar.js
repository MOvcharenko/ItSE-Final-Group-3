import { chosenTermId, download, getJson, rememberTerm } from './api.js';
import { addDays, dateRange, dayHeading, hourLabel, longDate, minutes, shortDate, timeRange, today } from './dates.js';
import { banner, el } from './dom.js';
import { openDetails } from './forms.js';
import { noticesPanel } from './notices.js';

const VIEWS = [['DAY', 'Day'], ['WEEK', 'Week'], ['TERM', 'Term']];
const FIRST_HOUR = 8;
const LAST_HOUR = 18;
const HOUR_PX = 72; // keep in step with --hour in app.css
const MIN_BLOCK_PX = 24;

/** The term to open with: the one running today (Published first), else the next to start, else the latest. */
function defaultTerm(terms, date) {
  const running = terms.filter((term) => term.startDate <= date && date <= term.endDate);
  const byStart = [...terms].sort((a, b) => a.startDate.localeCompare(b.startDate));
  return running.find((term) => term.status === 'PUBLISHED') ?? running[0]
    ?? byStart.find((term) => term.startDate > date) ?? byStart.at(-1);
}

/** Gives events that overlap on one day their own columns, side by side. */
function layoutOverlaps(events) {
  const sorted = [...events].sort((a, b) => minutes(a.start) - minutes(b.start) || minutes(a.end) - minutes(b.end));
  const placed = [];
  let cluster = [];
  let clusterEnd = -1;
  const closeCluster = () => {
    const columnEnds = [];
    const start = placed.length;
    for (const event of cluster) {
      let column = columnEnds.findIndex((end) => end <= minutes(event.start));
      if (column < 0) column = columnEnds.length;
      columnEnds[column] = minutes(event.end);
      placed.push({ event, column });
    }
    for (let i = start; i < placed.length; i++) placed[i].columns = columnEnds.length;
    cluster = [];
  };
  for (const event of sorted) {
    if (minutes(event.start) >= clusterEnd) {
      closeCluster();
      clusterEnd = -1;
    }
    cluster.push(event);
    clusterEnd = Math.max(clusterEnd, minutes(event.end));
  }
  closeCluster();
  return placed;
}

// How courses are named on the calendar. A per-viewer choice, so it lives in this browser only.
const COURSE_DISPLAYS = [['BOTH', 'Code and name'], ['CODE', 'Code'], ['NAME', 'Name']];
const COURSE_DISPLAY_KEY = 'calendar.courseDisplay';
let courseDisplay = savedCourseDisplay();

function savedCourseDisplay() {
  try {
    const saved = localStorage.getItem(COURSE_DISPLAY_KEY);
    return COURSE_DISPLAYS.some(([value]) => value === saved) ? saved : 'BOTH';
  } catch {
    return 'BOTH'; // storage can be blocked, e.g. in a private window
  }
}

function saveCourseDisplay(value) {
  courseDisplay = value;
  try {
    localStorage.setItem(COURSE_DISPLAY_KEY, value);
  } catch {
    // the choice then lasts until the page reloads
  }
}

function courseName(code, title) {
  return { CODE: code, NAME: title, BOTH: `${code} ${title}` }[courseDisplay];
}

/** The main label and, when showing both, the course name to follow it in lighter text. */
function courseParts(event) {
  const exam = event.pattern === 'EXAM' ? 'Exam · ' : '';
  if (courseDisplay === 'NAME') return [`${exam}${event.courseTitle} · ${event.sectionNumber}`, null];
  const code = `${exam}${event.courseCode} · ${event.sectionNumber}`;
  return [code, courseDisplay === 'BOTH' ? event.courseTitle : null];
}

function courseLabel(event) {
  return courseParts(event).filter(Boolean).join(' ');
}

function courseNodes(event) {
  const [main, title] = courseParts(event);
  return title ? [main, ' ', el('span', { class: 'course-title' }, title)] : [main];
}

function roomLabel(event) {
  return event.roomName ?? 'No room yet';
}

function instructorNames(event) {
  return event.instructors.map((person) => person.name).join(', ');
}

function showEventDetails(event) {
  const kind = { WEEKLY: 'Weekly class', ONE_TIME: 'One-time meeting', EXAM: 'Exam' }[event.pattern];
  const term = event.termStatus === 'DRAFT' ? `${event.termName} (Draft)` : event.termName;
  openDetails(`${event.courseCode}, section ${event.sectionNumber}`, [
    ['Course', event.courseTitle],
    ['Type', kind],
    ['When', `${longDate(event.date)}, ${timeRange(event.start, event.end)}`],
    ['Room', roomLabel(event)],
    ['Building', event.building ?? '—'],
    [event.instructors.length === 1 ? 'Instructor' : 'Instructors', instructorNames(event) || '—'],
    ['Term', term],
  ]);
}

function eventBlock({ event, column, columns }, firstHour) {
  const top = (minutes(event.start) - firstHour * 60) * HOUR_PX / 60;
  const height = Math.max((minutes(event.end) - minutes(event.start)) * HOUR_PX / 60, MIN_BLOCK_PX);
  const width = 100 / columns;
  const when = timeRange(event.start, event.end);
  const classes = ['block', event.termStatus === 'DRAFT' ? 'draft' : 'published', event.pattern === 'EXAM' && 'exam',
    columns > 1 && 'narrow'];
  return el('button', {
    class: classes.filter(Boolean).join(' '),
    type: 'button',
    style: `top:${top}px;height:${height}px;left:calc(${column * width}% + 2px);width:calc(${width}% - 4px)`,
    'aria-label': `${courseLabel(event)}, ${shortDate(event.date)}, ${when}, ${roomLabel(event)}. Show details`,
    // A narrow block cuts the name off, so hovering shows it whole.
    title: `${event.courseCode} · ${event.sectionNumber} ${event.courseTitle}\n${when}, ${roomLabel(event)}`,
    onclick: () => showEventDetails(event),
  },
    el('span', { class: 'course' }, courseNodes(event)),
    el('span', {}, when),
    el('span', { class: 'room' }, roomLabel(event)));
}

function weekDays(view) {
  const days = [];
  for (let date = view.from; date <= view.to; date = addDays(date, 1)) days.push(date);
  // Saturday and Sunday only take up room when something is on them.
  const hasEvents = (date) => view.events.some((event) => event.date === date);
  return days.filter((date, index) => index < 5 || hasEvents(date));
}

function timeGrid(view) {
  const days = weekDays(view);
  const earliest = Math.min(FIRST_HOUR * 60, ...view.events.map((event) => minutes(event.start)));
  const latest = Math.max(LAST_HOUR * 60, ...view.events.map((event) => minutes(event.end)));
  const firstHour = Math.floor(earliest / 60);
  const lastHour = Math.ceil(latest / 60);
  const height = (lastHour - firstHour) * HOUR_PX;

  const hourLabels = [];
  for (let hour = firstHour; hour < lastHour; hour++) {
    hourLabels.push(el('div', { class: 'grid-time', style: `top:${(hour - firstHour) * HOUR_PX}px` }, hourLabel(hour)));
  }
  const currentDay = today();
  return el('div', { class: 'scroll-x' },
    el('div', { class: days.length === 1 ? 'grid single' : 'grid' },
      el('div', { class: 'grid-head' },
        el('div', { class: 'grid-corner' }),
        days.map((date) => el('div', { class: date === currentDay ? 'grid-day today' : 'grid-day' }, dayHeading(date)))),
      el('div', { class: 'grid-body' },
        el('div', { class: 'grid-times', style: `height:${height}px` }, hourLabels),
        days.map((date) => el('div', { class: 'grid-col', style: `height:${height}px` },
          layoutOverlaps(view.events.filter((event) => event.date === date))
            .map((placed) => eventBlock(placed, firstHour)))))));
}

function termList(view) {
  let lastDate = null;
  const rows = view.events.map((event) => {
    const date = event.date === lastDate ? '' : shortDate(event.date);
    lastDate = event.date;
    return el('tr', {},
      el('td', { class: 'date' }, date),
      el('td', { class: 'time' }, timeRange(event.start, event.end)),
      el('td', {}, courseNodes(event)),
      el('td', {}, roomLabel(event)),
      el('td', {}, instructorNames(event)));
  });
  return el('div', { class: 'scroll-x' },
    el('table', {},
      el('thead', {}, el('tr', {}, ['Date', 'Time', 'Course', 'Room', 'Instructors'].map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, rows)));
}

function filterField(id, label, onchange) {
  const select = el('select', { id, onchange });
  return { select, field: el('div', { class: 'field' }, el('label', { for: id }, label), select) };
}

/** Fills a filter from what has been seen so far, keeping the choice even if the current view doesn't contain it. */
function fillFilter(select, allLabel, known) {
  const value = select.value;
  const options = [...known.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  select.replaceChildren(el('option', { value: '' }, allLabel),
    ...options.map(([optionValue, label]) => el('option', { value: optionValue }, label)));
  select.value = value;
}

/** The calendar screen for any role. The API limits the events to what this account may see. */
export function calendarScreen(user, setTopbar) {
  // A phone is too narrow for a week without sideways scrolling.
  const state = { view: matchMedia('(max-width: 760px)').matches ? 'DAY' : 'WEEK', date: today(), term: null };
  const known = { course: new Map(), instructor: new Map(), building: new Map(), room: new Map() };
  let terms = [];
  let requestNumber = 0;

  const body = el('div', { 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading calendar…'));
  const messages = el('div', { class: 'messages' });
  const heading = el('h2');
  const meta = el('span', { class: 'meta' });

  const termSelect = el('select', { id: 'term', 'aria-label': 'Term', onchange: () => selectTerm(termSelect.value) });
  const termTag = el('span', { class: 'tag' });
  const exportButton = el('button', { class: 'btn', type: 'button', disabled: true, onclick: exportTerm }, 'Export .ics');
  const course = filterField('filter-course', 'Course', load);
  const instructor = filterField('filter-instructor', 'Instructor', load);
  const building = filterField('filter-building', 'Building', load);
  const room = filterField('filter-room', 'Room', load);
  // A display setting, not a filter, so it sits in the panel header rather than among the filters.
  const displaySelect = el('select', {
    id: 'course-display',
    onchange: () => {
      saveCourseDisplay(displaySelect.value);
      if (lastView) showView(lastView);
    },
  }, COURSE_DISPLAYS.map(([value, label]) => el('option', { value }, label)));
  displaySelect.value = courseDisplay;
  let lastView = null;

  const viewButtons = VIEWS.map(([value, label]) => el('button', {
    type: 'button',
    'aria-pressed': String(value === state.view),
    onclick: () => { state.view = value; load(); },
  }, label));
  const stepper = el('div', { class: 'stepper', hidden: true },
    el('button', { class: 'btn', type: 'button', 'aria-label': 'Previous', onclick: () => step(-1) }, '←'),
    el('button', { class: 'btn', type: 'button', onclick: () => { state.date = today(); load(); } }, 'Today'),
    el('button', { class: 'btn', type: 'button', 'aria-label': 'Next', onclick: () => step(1) }, '→'));

  function step(direction) {
    state.date = addDays(state.date, direction * (state.view === 'DAY' ? 1 : 7));
    load();
  }

  function filters() {
    const params = new URLSearchParams({ termId: state.term.id });
    if (course.select.value) params.set('course', course.select.value);
    if (instructor.select.value) params.set('instructor', instructor.select.value);
    if (building.select.value) params.set('building', building.select.value);
    if (room.select.value) params.set('room', room.select.value);
    return params;
  }

  function hasFilters() {
    return Boolean(course.select.value || instructor.select.value || building.select.value || room.select.value);
  }

  function clearFilters() {
    course.select.value = instructor.select.value = building.select.value = room.select.value = '';
    load();
  }

  function selectTerm(id) {
    state.term = terms.find((term) => term.id === id);
    rememberTerm(id);
    clearFilterChoices();
    if (state.date < state.term.startDate || state.date > state.term.endDate) state.date = state.term.startDate;
    showTermInTopbar();
    load();
  }

  function clearFilterChoices() {
    Object.values(known).forEach((values) => values.clear());
    course.select.value = instructor.select.value = building.select.value = room.select.value = '';
  }

  function emptyState() {
    const { term } = state;
    if (hasFilters()) {
      return el('div', { class: 'empty' }, el('strong', {}, 'Nothing matches these filters'),
        el('button', { class: 'btn', type: 'button', onclick: clearFilters }, 'Clear filters'));
    }
    const period = { DAY: 'on this day', WEEK: 'this week', TERM: 'in this term' }[state.view];
    if (state.view !== 'TERM' && (state.date < term.startDate || state.date > term.endDate)) {
      return el('div', { class: 'empty' },
        el('strong', {}, `Outside ${term.name}`),
        `This term runs ${dateRange(term.startDate, term.endDate)}.`,
        el('button', { class: 'btn', type: 'button', onclick: () => { state.date = term.startDate; load(); } }, 'Go to the start of the term'));
    }
    return el('div', { class: 'empty' }, el('strong', {}, `Nothing scheduled ${period}`));
  }

  function showView(view) {
    lastView = view;
    view.events.forEach((event) => {
      known.course.set(event.courseCode, event.courseTitle);
      event.instructors.forEach((person) => known.instructor.set(person.id, person.name ?? person.id));
      if (event.building) known.building.set(event.building, event.building);
      if (event.roomId) known.room.set(event.roomId, event.roomName);
    });
    fillFilter(course.select, 'All courses',
      new Map([...known.course].map(([code, title]) => [code, courseName(code, title)])));
    fillFilter(instructor.select, 'All instructors', known.instructor);
    fillFilter(building.select, 'All buildings', known.building);
    fillFilter(room.select, 'All rooms', known.room);

    heading.textContent = {
      DAY: longDate(view.from),
      WEEK: dateRange(view.from, view.to),
      TERM: `${state.term.name}: ${dateRange(view.from, view.to)}`,
    }[view.scope];
    meta.textContent = view.events.length === 1 ? '1 meeting' : `${view.events.length} meetings`;

    // An empty list here means the SIS gave no answer, not that the student has no classes.
    messages.replaceChildren(...(view.enrollmentAvailable ? [] : [banner(
      'Your enrollment could not be loaded right now, so your classes can’t be shown. This doesn’t mean you have no classes.',
      'Try again', load)]));
    if (!view.enrollmentAvailable) body.replaceChildren();
    else if (!view.events.length) body.replaceChildren(emptyState());
    else body.replaceChildren(view.scope === 'TERM' ? termList(view) : timeGrid(view));
  }

  async function load() {
    const thisRequest = ++requestNumber;
    viewButtons.forEach((button, index) => button.setAttribute('aria-pressed', String(VIEWS[index][0] === state.view)));
    stepper.hidden = state.view === 'TERM';
    const params = filters();
    params.set('view', state.view);
    params.set('date', state.date);
    body.setAttribute('aria-busy', 'true');
    try {
      const view = await getJson(`/api/calendar?${params}`);
      if (thisRequest === requestNumber) showView(view);
    } catch (error) {
      if (thisRequest === requestNumber) {
        body.replaceChildren();
        messages.replaceChildren(banner(error.message, 'Try again', load));
      }
    } finally {
      if (thisRequest === requestNumber) body.removeAttribute('aria-busy');
    }
  }

  async function exportTerm() {
    exportButton.disabled = true;
    try {
      const safeName = state.term.name.replace(/[^\w -]+/g, '').trim() || 'calendar';
      await download(`/api/calendar.ics?${filters()}`, `${safeName}.ics`);
      messages.replaceChildren();
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    } finally {
      exportButton.disabled = false;
    }
  }

  function showTermInTopbar() {
    const draft = state.term.status === 'DRAFT';
    termTag.className = draft ? 'tag draft' : 'tag published';
    termTag.textContent = draft ? 'Draft' : 'Published';
    termSelect.value = state.term.id;
    exportButton.disabled = false;
    setTopbar([termSelect, termTag], [exportButton]);
  }

  async function start() {
    try {
      terms = await getJson('/api/terms');
      // Instructors can list Drafts, but only Published terms have anything on their calendar.
      if (user.role !== 'SCHEDULER') terms = terms.filter((term) => term.status === 'PUBLISHED');
    } catch (error) {
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => { messages.replaceChildren(); start(); }));
      return;
    }
    if (!terms.length) {
      calendarPanel.replaceChildren(el('div', { class: 'empty' }, el('strong', {}, 'No terms to show yet'),
        user.role === 'SCHEDULER'
          ? el('a', { class: 'btn primary', href: '#timetable' }, 'Create a term in Timetable')
          : 'Your schedule will appear here once a term is published.'));
      return;
    }
    termSelect.replaceChildren(...[...terms].sort((a, b) => b.startDate.localeCompare(a.startDate))
      .map((term) => el('option', { value: term.id }, term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name)));
    state.term = terms.find((term) => term.id === chosenTermId()) ?? defaultTerm(terms, state.date);
    if (state.date < state.term.startDate || state.date > state.term.endDate) state.date = state.term.startDate;
    showTermInTopbar();
    toolbar.hidden = false;
    load();
  }

  const toolbar = el('div', { class: 'toolbar calendar-filters', hidden: true },
    el('div', { class: 'segmented', role: 'group', 'aria-label': 'Calendar view' }, viewButtons),
    el('div', { class: 'spacer' }),
    course.field, instructor.field, building.field, room.field);
  const calendarPanel = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('div', { class: 'period' }, stepper, heading),
      el('div', { class: 'head-tools' }, meta,
        el('label', { class: 'inline-field' }, 'Show courses as', displaySelect))),
    toolbar,
    body);

  start();
  return el('div', { class: 'content' },
    messages,
    el('div', { class: 'two-col' }, calendarPanel, noticesPanel()));
}
