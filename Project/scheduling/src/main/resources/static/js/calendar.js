import { addDays, dateRange, dayHeading, hourLabel, longDate, minutes, shortDate, timeRange, today } from './dates.js';
import { banner, dataTable, el } from './dom.js';
import { field, openDetails, select } from './forms.js';
import { latestOnly, Superseded } from './http.js';
import { plural } from './labels.js';
import { layoutOverlaps } from './layout.js';
import { noticesPanel } from './notices.js';
import { api } from './scheduling-api.js';
import { initialTerm, termLabel, termPicker } from './terms.js';

const VIEWS = [['DAY', 'Day'], ['WEEK', 'Week'], ['TERM', 'Term']];
const FIRST_HOUR = 8;
const LAST_HOUR = 18;
const HOUR_PX = 72; // keep in step with --hour in app.css
const MIN_BLOCK_PX = 24;

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
  openDetails(`${event.courseCode}, section ${event.sectionNumber}`, [
    ['Course', event.courseTitle],
    ['Type', kind],
    ['When', `${longDate(event.date)}, ${timeRange(event.start, event.end)}`],
    ['Room', roomLabel(event)],
    ['Building', event.building ?? '—'],
    [event.instructors.length === 1 ? 'Instructor' : 'Instructors', instructorNames(event) || '—'],
    ['Term', termLabel({ name: event.termName, status: event.termStatus })],
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

/** The Term view: one row per meeting, the date only on the first of each day. */
function termList(view) {
  const firstOfDay = new Set(view.events.filter((event, index) => event.date !== view.events[index - 1]?.date));
  return dataTable([
    { label: 'Date', class: 'date', cell: (event) => (firstOfDay.has(event) ? shortDate(event.date) : '') },
    { label: 'Time', class: 'time', cell: (event) => timeRange(event.start, event.end) },
    { label: 'Course', cell: courseNodes },
    { label: 'Room', cell: roomLabel },
    { label: 'Instructors', cell: instructorNames },
  ], view.events);
}

/** Fills a filter from what has been seen so far, keeping the choice even if the current view doesn't contain it. */
function fillFilter(control, allLabel, known) {
  const value = control.value;
  const options = [...known.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  control.replaceChildren(el('option', { value: '' }, allLabel),
    ...options.map(([optionValue, label]) => el('option', { value: optionValue }, label)));
  control.value = value;
}

/** The calendar screen for any role. The API limits the events to what this account may see. */
export function calendarScreen(user, setTopbar) {
  // A phone is too narrow for a week without sideways scrolling.
  const state = { view: matchMedia('(max-width: 760px)').matches ? 'DAY' : 'WEEK', date: today(), term: null };
  const known = { course: new Map(), instructor: new Map(), building: new Map(), room: new Map() };
  const fetchView = latestOnly((filters) => api.calendar(filters));

  const body = el('div', { 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading calendar…'));
  const messages = el('div', { class: 'messages' });
  const heading = el('h2');
  const meta = el('span', { class: 'meta' });

  const picker = termPicker((term) => selectTerm(term));
  const exportButton = el('button', { class: 'btn', type: 'button', disabled: true, onclick: exportTerm }, 'Export .ics');
  const filterSelects = {
    course: select('course', [], '', { id: 'filter-course', onchange: load }),
    instructor: select('instructor', [], '', { id: 'filter-instructor', onchange: load }),
    building: select('building', [], '', { id: 'filter-building', onchange: load }),
    room: select('room', [], '', { id: 'filter-room', onchange: load }),
  };
  // A display setting, not a filter, so it sits in the panel header rather than among the filters.
  const displaySelect = select('courseDisplay', COURSE_DISPLAYS, courseDisplay, {
    id: 'course-display',
    onchange: () => {
      saveCourseDisplay(displaySelect.value);
      if (lastView) showView(lastView);
    },
  });
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

  /** The term and the filters chosen; blank filters are left out of the request. */
  function filters() {
    return { termId: state.term.id, ...Object.fromEntries(Object.entries(filterSelects).map(([key, control]) => [key, control.value])) };
  }

  function hasFilters() {
    return Object.values(filterSelects).some((control) => control.value);
  }

  function clearFilters() {
    Object.values(filterSelects).forEach((control) => { control.value = ''; });
    load();
  }

  function selectTerm(term) {
    state.term = term;
    Object.values(known).forEach((values) => values.clear());
    Object.values(filterSelects).forEach((control) => { control.value = ''; });
    if (state.date < term.startDate || state.date > term.endDate) state.date = term.startDate;
    picker.show(term);
    exportButton.disabled = false;
    setTopbar([picker.select, picker.tag], [exportButton]);
    load();
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
    fillFilter(filterSelects.course, 'All courses',
      new Map([...known.course].map(([code, title]) => [code, courseName(code, title)])));
    fillFilter(filterSelects.instructor, 'All instructors', known.instructor);
    fillFilter(filterSelects.building, 'All buildings', known.building);
    fillFilter(filterSelects.room, 'All rooms', known.room);

    heading.textContent = {
      DAY: longDate(view.from),
      WEEK: dateRange(view.from, view.to),
      TERM: `${state.term.name}: ${dateRange(view.from, view.to)}`,
    }[view.scope];
    meta.textContent = plural(view.events.length, 'meeting');

    // An empty list here means the SIS gave no answer, not that the student has no classes.
    messages.replaceChildren(...(view.enrollmentAvailable ? [] : [banner(
      'Your enrollment could not be loaded right now, so your classes can’t be shown. This doesn’t mean you have no classes.',
      'Try again', load)]));
    if (!view.enrollmentAvailable) body.replaceChildren();
    else if (!view.events.length) body.replaceChildren(emptyState());
    else body.replaceChildren(view.scope === 'TERM' ? termList(view) : timeGrid(view));
  }

  async function load() {
    viewButtons.forEach((button, index) => button.setAttribute('aria-pressed', String(VIEWS[index][0] === state.view)));
    stepper.hidden = state.view === 'TERM';
    body.setAttribute('aria-busy', 'true');
    try {
      showView(await fetchView({ ...filters(), view: state.view, date: state.date }));
    } catch (error) {
      if (error instanceof Superseded) return;
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', load));
    }
    body.removeAttribute('aria-busy');
  }

  async function exportTerm() {
    exportButton.disabled = true;
    try {
      const safeName = state.term.name.replace(/[^\w -]+/g, '').trim() || 'calendar';
      await api.downloadCalendar(filters(), `${safeName}.ics`);
      messages.replaceChildren();
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    } finally {
      exportButton.disabled = false;
    }
  }

  async function start() {
    let terms;
    try {
      terms = await api.terms();
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
    picker.setTerms(terms);
    toolbar.hidden = false;
    selectTerm(initialTerm(terms));
  }

  const toolbar = el('div', { class: 'toolbar calendar-filters', hidden: true },
    el('div', { class: 'segmented', role: 'group', 'aria-label': 'Calendar view' }, viewButtons),
    el('div', { class: 'spacer' }),
    field('Course', filterSelects.course), field('Instructor', filterSelects.instructor),
    field('Building', filterSelects.building), field('Room', filterSelects.room));
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
