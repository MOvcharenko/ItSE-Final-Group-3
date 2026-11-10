import { banner, dataTable, el } from './dom.js';
import { latestOnly, Superseded } from './http.js';
import { meetingLabel, plural, shortDay } from './labels.js';
import { api } from './scheduling-api.js';
import { initialTerm, termPicker } from './terms.js';

/** "2 hours", "2 h 5 min" or "45 min" a week. */
function contactTime(minutes) {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (!hours) return `${rest} min`;
  if (!rest) return plural(hours, 'hour');
  return `${hours} h ${rest} min`;
}

function schedulePanel(teaching, me) {
  const { term, sections } = teaching;
  const head = el('div', { class: 'panel-head' },
    el('h2', {}, 'Teaching schedule'),
    el('span', { class: 'meta' }, `${term.name} · ${plural(sections.length, 'section')}`));
  if (!sections.length) {
    return el('section', { class: 'panel' }, head, el('div', { class: 'empty' },
      el('strong', {}, `You’re not teaching any sections in ${term.name}`),
      'Sections a Scheduler assigns to you will appear here.'));
  }
  return el('section', { class: 'panel' }, head, dataTable([
    { label: 'Course', cell: (section) => el('div', {},
      el('div', {}, el('strong', {}, `${section.courseCode} · ${section.sectionNumber}`)),
      el('div', { class: 'muted' }, section.courseTitle)) },
    { label: 'Meets', cell: (section) => el('div', {},
      section.meetings.map((meeting) => el('div', { class: 'nowrap' }, meetingLabel(meeting)))) },
    { label: 'Room', cell: (section) => (section.roomName
      ? el('div', {}, el('div', {}, section.roomName), el('div', { class: 'muted' }, section.building))
      : el('span', { class: 'tag cancelled' }, 'No room yet')) },
    { label: 'Teaching with', cell: (section) => section.instructors
      .filter((person) => person.id !== me).map((person) => person.name).join(', ') || '—' },
  ], sections, { cards: true }));
}

function workloadPanel({ sections, workload }) {
  const byCourse = new Map();
  sections.forEach((section) => {
    const row = byCourse.get(section.courseCode) ?? { code: section.courseCode, sections: 0, credits: 0 };
    row.sections += 1;
    row.credits += section.creditHours;
    byCourse.set(section.courseCode, row);
  });
  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'Workload')),
    sections.length > 0 && dataTable([
      { label: 'Course', cell: (row) => row.code },
      { label: 'Sections', cell: (row) => String(row.sections) },
      { label: 'Credit hrs', cell: (row) => String(row.credits) },
    ], [...byCourse.values()]),
    el('dl', { class: 'totals' },
      el('div', {}, el('dt', {}, 'Sections'), el('dd', {}, String(workload.sections))),
      el('div', {}, el('dt', {}, 'Credit hours'), el('dd', {}, String(workload.creditHours))),
      el('div', {}, el('dt', {}, 'Weekly hours'), el('dd', {}, contactTime(workload.weeklyContactMinutes)))),
    el('p', { class: 'hint panel-body' }, 'Weekly hours count time in class each week. One-time meetings and exams are listed in your schedule.'));
}

// --- the availability declaration (UC-14, TR2.7, NFR3.4, NFR4.3) -------------

const GRID_DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'];
const SLOTS = [
  { start: '08:00:00', end: '10:00:00', label: '08:00 – 10:00' },
  { start: '10:00:00', end: '12:00:00', label: '10:00 – 12:00' },
  { start: '12:00:00', end: '14:00:00', label: '12:00 – 14:00' },
  { start: '14:00:00', end: '16:00:00', label: '14:00 – 16:00' },
  { start: '16:00:00', end: '18:00:00', label: '16:00 – 18:00' },
];
const SLOT_HOURS = 2;
const NEXT_STATUS = { OPEN: 'PREFER', PREFER: 'UNAVAILABLE', UNAVAILABLE: 'OPEN' };

/**
 * The week of two-hour slots, each Open, Prefer or Unavailable; a click moves a slot to the next. The grid owns the
 * slots: blocks() lists every one, as the API takes them. onChange runs after each click.
 */
function availabilityGrid(savedBlocks, { readOnly, onChange }) {
  const statuses = new Map(savedBlocks.map((block) => [`${block.dayOfWeek}_${block.startTime}`, block.status]));
  const statusOf = (day, slot) => statuses.get(`${day}_${slot.start}`) ?? 'OPEN';

  function cell(day, slot) {
    const button = el('button', {
      type: 'button',
      class: `availability-grid-cell avail-${statusOf(day, slot)}`,
      disabled: readOnly,
      onclick: () => {
        const next = NEXT_STATUS[statusOf(day, slot)];
        statuses.set(`${day}_${slot.start}`, next);
        button.className = `availability-grid-cell avail-${next}`;
        button.textContent = next;
        onChange();
      },
    }, statusOf(day, slot));
    return button;
  }

  return {
    element: dataTable([
      { label: 'Time Window', cell: (slot) => slot.label },
      ...GRID_DAYS.map((day) => ({ label: shortDay(day).toUpperCase(), cell: (slot) => cell(day, slot) })),
    ], SLOTS, { cards: true }),
    blocks: () => GRID_DAYS.flatMap((day) => SLOTS.map((slot) =>
      ({ dayOfWeek: day, startTime: slot.start, endTime: slot.end, status: statusOf(day, slot) }))),
  };
}

/** Hours per status in the declared week. update(blocks) recounts. */
function availabilitySummary() {
  const element = el('dl', { class: 'totals' });
  const update = (blocks) => {
    const hours = (status) => blocks.filter((block) => block.status === status).length * SLOT_HOURS;
    element.replaceChildren(
      el('div', {}, el('dt', {}, 'Open hours'), el('dd', {}, `${hours('OPEN')} h`)),
      el('div', {}, el('dt', {}, 'Preferred hours'), el('dd', {}, `${hours('PREFER')} h`)),
      el('div', {}, el('dt', {}, 'Unavailable'), el('dd', {}, `${hours('UNAVAILABLE')} h`)),
      el('div', {}, el('dt', {}, 'Total active'), el('dd', {}, `${hours('OPEN') + hours('PREFER')} h`)));
  };
  return { element, update };
}

/**
 * The equipment types to offer: those in the catalog, plus any this instructor already asked for that the catalog
 * no longer has, so a saved request stays visible and can be unticked. One entry per type whatever its case.
 */
function equipmentChoices(catalogTypes, requested) {
  const unique = new Map();
  [...catalogTypes, ...requested].forEach((type) => {
    if (!unique.has(type.toLowerCase())) unique.set(type.toLowerCase(), type);
  });
  return [...unique.values()].sort((a, b) => a.localeCompare(b));
}

/** A checkbox per equipment type. value() lists the ticked ones. */
function equipmentNeeds(catalogTypes, requested, { readOnly }) {
  const chosen = new Set(requested);
  const choices = equipmentChoices(catalogTypes, requested);
  const element = choices.length
    ? choices.map((type) => {
      const box = el('input', {
        type: 'checkbox',
        checked: chosen.has(type),
        disabled: readOnly,
        onchange: () => { if (box.checked) chosen.add(type); else chosen.delete(type); },
      });
      return el('label', { class: 'checkbox-inline', style: 'margin-right: 16px;' }, box, ` ${type}`);
    })
    : el('p', { class: 'hint' }, 'The catalog has no portable equipment yet.');
  return { element, value: () => [...chosen] };
}

function notesField(saved, { readOnly }) {
  const element = el('textarea', {
    class: 'input wide',
    rows: 2,
    disabled: readOnly,
    placeholder: 'Optional notes for scheduler regarding your availability or equipment needs...',
  }, saved ?? '');
  return { element, value: () => element.value };
}

/**
 * The instructor's declaration for one term: when they can teach, what equipment their courses need, and a note.
 * equipmentTypes are the catalog's item types. savedMessage, if any, says the last save worked: onSaved(message)
 * rebuilds the panel after a save.
 */
function availabilityDeclarationPanel(termId, declaration, equipmentTypes, savedMessage, onSaved) {
  const { readOnly } = declaration;
  const messages = el('div', { class: 'panel-message' });
  const status = el('p', { class: 'status', role: 'status' }, savedMessage ?? '');
  const summary = availabilitySummary();
  const grid = availabilityGrid(declaration.blocks, { readOnly, onChange: () => summary.update(grid.blocks()) });
  summary.update(grid.blocks());
  const needs = equipmentNeeds(equipmentTypes, declaration.equipmentNeeds ?? [], { readOnly });
  const notes = notesField(declaration.notes, { readOnly });

  const save = el('button', {
    class: 'btn primary',
    type: 'button',
    onclick: async () => {
      messages.replaceChildren();
      status.textContent = '';
      save.disabled = true;
      try {
        await api.saveMyAvailability(termId, { blocks: grid.blocks(), equipmentNeeds: needs.value(), notes: notes.value() });
        onSaved('Availability and equipment needs saved.');
      } catch (error) {
        messages.replaceChildren(banner(error.message));
      } finally {
        save.disabled = false;
      }
    },
  }, 'Submit availability');

  const legend = el('div', { class: 'hint', style: 'margin-bottom: 12px;' },
    'Click blocks to cycle: ',
    el('span', { class: 'tag insight-success' }, 'OPEN'), ' → ',
    el('span', { class: 'tag insight-tag' }, 'PREFER (Advisory)'), ' → ',
    el('span', { class: 'tag insight-conflict-tag' }, 'UNAVAILABLE (Strict)'));

  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, 'Availability and equipment needs'),
      readOnly && el('span', { class: 'tag' }, 'Locked (Published)')),
    el('div', { class: 'panel-body' },
      readOnly && el('p', { class: 'hint' }, 'This term is Published. Instructor availability is locked and cannot be changed retroactively (NFR3.4).'),
      messages,
      status,
      legend,
      grid.element,
      summary.element,
      el('h3', { style: 'margin-top: 16px;' }, 'Course portable equipment requests'),
      el('div', { style: 'margin: 8px 0 16px 0;' }, needs.element),
      el('h3', {}, 'Notes for scheduler'),
      el('div', { style: 'margin: 8px 0 16px 0;' }, notes.element),
      !readOnly && save));
}

/** My Teaching: the signed-in instructor's own sections, workload, and availability (UC-14). */
export function teachingScreen(user, setTopbar) {
  const messages = el('div', { class: 'messages' });
  const body = el('div', { class: 'teaching', 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading your teaching…'));
  const picker = termPicker((term) => selectTerm(term));
  const calendarLink = el('a', { class: 'btn', href: '#calendar' }, 'Open My Calendar');
  const fetchTerm = latestOnly((term) => Promise.all([
    // A Draft has no teaching schedule yet, only the availability to declare for it.
    term.status === 'DRAFT' ? null : api.teaching(term.id),
    api.myAvailability(term.id),
    api.equipment(),
  ]));

  function selectTerm(term) {
    picker.show(term);
    setTopbar([picker.select, picker.tag], [calendarLink]);
    load(term);
  }

  /** savedMessage: after a save, what the rebuilt availability panel should confirm. */
  async function load(term, savedMessage) {
    body.setAttribute('aria-busy', 'true');
    try {
      const [teaching, declaration, equipment] = await fetchTerm(term);
      messages.replaceChildren();
      body.replaceChildren(
        teaching
          ? el('div', { class: 'two-col' }, schedulePanel(teaching, user.id), workloadPanel(teaching))
          : el('section', { class: 'panel' }, el('div', { class: 'empty' },
            el('strong', {}, 'Teaching schedule under construction'),
            'Course assignments and workload will appear here once the term is published.')),
        availabilityDeclarationPanel(term.id, declaration, equipment.map((item) => item.itemType), savedMessage,
          (message) => load(term, message)));
    } catch (error) {
      if (error instanceof Superseded) return;
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => load(term)));
    }
    body.removeAttribute('aria-busy');
  }

  async function start() {
    let terms;
    try {
      terms = await api.terms();
    } catch (error) {
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => { messages.replaceChildren(); start(); }));
      return;
    }
    if (!terms.length) {
      body.replaceChildren(el('section', { class: 'panel' }, el('div', { class: 'empty' },
        el('strong', {}, 'No terms to show yet'),
        'Terms will appear here once created by a Scheduler.')));
      return;
    }
    picker.setTerms(terms);
    selectTerm(initialTerm(terms));
  }

  start();
  return el('div', { class: 'content' }, messages, body);
}
