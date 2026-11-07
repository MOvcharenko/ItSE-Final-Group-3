import { chosenTermId, getJson, put, rememberTerm } from './api.js';
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

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'];
const SLOTS = [
  { start: '08:00:00', end: '10:00:00', label: '08:00 – 10:00' },
  { start: '10:00:00', end: '12:00:00', label: '10:00 – 12:00' },
  { start: '12:00:00', end: '14:00:00', label: '12:00 – 14:00' },
  { start: '14:00:00', end: '16:00:00', label: '14:00 – 16:00' },
  { start: '16:00:00', end: '18:00:00', label: '16:00 – 18:00' },
];
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

/**
 * Interactive availability submission panel (UC-14, TR2.7, NFR3.4, NFR4.3). equipmentTypes are the catalog's item
 * types; savedMessage, if any, says the last save worked (the panel is rebuilt after a save).
 */
function availabilityPanel(termId, availabilityData, equipmentTypes, savedMessage, onSaved) {
  const readOnly = availabilityData.readOnly;
  const blocksMap = new Map();
  availabilityData.blocks.forEach((b) => {
    blocksMap.set(`${b.dayOfWeek}_${b.startTime}`, b.status);
  });

  const selectedEquipment = new Set(availabilityData.equipmentNeeds || []);
  let notesValue = availabilityData.notes || '';
  const messageEl = el('div', { class: 'panel-message' });
  const status = el('p', { class: 'status', role: 'status' }, savedMessage ?? '');

  function nextStatus(curr) {
    if (!curr || curr === 'OPEN') return 'PREFER';
    if (curr === 'PREFER') return 'UNAVAILABLE';
    return 'OPEN';
  }

  const gridTable = el('table', { class: 'cards' },
    el('thead', {},
      el('tr', {},
        el('th', {}, 'Time Window'),
        ...DAYS.map((d) => el('th', {}, d.slice(0, 3))))),
    el('tbody', {},
      SLOTS.map((slot) => {
        const cells = DAYS.map((day) => {
          const key = `${day}_${slot.start}`;
          const currentStatus = blocksMap.get(key) || 'OPEN';
          const cellBtn = el('button', {
            type: 'button',
            class: `availability-grid-cell avail-${currentStatus}`,
            disabled: readOnly,
            onclick: () => {
              const updated = nextStatus(blocksMap.get(key));
              blocksMap.set(key, updated);
              cellBtn.className = `availability-grid-cell avail-${updated}`;
              cellBtn.textContent = updated;
              updateSummary();
            },
          }, currentStatus);
          return el('td', {}, cellBtn);
        });
        return el('tr', {}, el('td', {}, slot.label), ...cells);
      })));

  const summaryStrip = el('dl', { class: 'totals' });

  function updateSummary() {
    let openH = 0, preferH = 0, unavailH = 0;
    DAYS.forEach((d) => {
      SLOTS.forEach((s) => {
        const st = blocksMap.get(`${d}_${s.start}`) || 'OPEN';
        if (st === 'OPEN') openH += 2;
        else if (st === 'PREFER') preferH += 2;
        else if (st === 'UNAVAILABLE') unavailH += 2;
      });
    });
    summaryStrip.replaceChildren(
      el('div', {}, el('dt', {}, 'Open hours'), el('dd', {}, `${openH} h`)),
      el('div', {}, el('dt', {}, 'Preferred hours'), el('dd', {}, `${preferH} h`)),
      el('div', {}, el('dt', {}, 'Unavailable'), el('dd', {}, `${unavailH} h`)),
      el('div', {}, el('dt', {}, 'Total active'), el('dd', {}, `${openH + preferH} h`)));
  }
  updateSummary();

  const choices = equipmentChoices(equipmentTypes, [...selectedEquipment]);
  const equipCheckboxes = choices.length ? choices.map((item) => {
    const cb = el('input', {
      type: 'checkbox',
      checked: selectedEquipment.has(item),
      disabled: readOnly,
      onchange: () => {
        if (cb.checked) selectedEquipment.add(item);
        else selectedEquipment.delete(item);
      },
    });
    return el('label', { class: 'checkbox-inline', style: 'margin-right: 16px;' }, cb, ` ${item}`);
  }) : el('p', { class: 'hint' }, 'The catalog has no portable equipment yet.');

  const notesInput = el('textarea', {
    class: 'input wide',
    rows: 2,
    disabled: readOnly,
    placeholder: 'Optional notes for scheduler regarding your availability or equipment needs...',
    oninput: (e) => { notesValue = e.target.value; },
  }, notesValue);

  const saveBtn = el('button', {
    class: 'btn primary',
    type: 'button',
    disabled: readOnly,
    onclick: async () => {
      messageEl.replaceChildren();
      status.textContent = '';
      saveBtn.disabled = true;
      try {
        const blocks = [];
        DAYS.forEach((d) => {
          SLOTS.forEach((s) => {
            const st = blocksMap.get(`${d}_${s.start}`) || 'OPEN';
            blocks.push({ dayOfWeek: d, startTime: s.start, endTime: s.end, status: st });
          });
        });
        await put(`/api/terms/${encodeURIComponent(termId)}/availability/me`, {
          blocks,
          equipmentNeeds: [...selectedEquipment],
          notes: notesValue,
        });
        // Reloads the panel, which then shows the saved message as its status line.
        if (onSaved) onSaved('Availability and equipment needs saved.');
        else status.textContent = 'Availability and equipment needs saved.';
      } catch (err) {
        messageEl.replaceChildren(banner(err.message));
      } finally {
        saveBtn.disabled = readOnly;
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
      messageEl,
      status,
      legend,
      gridTable,
      summaryStrip,
      el('h3', { style: 'margin-top: 16px;' }, 'Course portable equipment requests'),
      el('div', { style: 'margin: 8px 0 16px 0;' }, equipCheckboxes),
      el('h3', {}, 'Notes for scheduler'),
      el('div', { style: 'margin: 8px 0 16px 0;' }, notesInput),
      !readOnly && saveBtn));
}

/** My Teaching: the signed-in instructor's own sections, workload, and availability (UC-14). */
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

  /** savedMessage: after a save, what the rebuilt availability panel should confirm. */
  async function load(termId, savedMessage) {
    const thisRequest = ++requestNumber;
    body.setAttribute('aria-busy', 'true');
    try {
      const isDraft = terms.find((t) => t.id === termId)?.status === 'DRAFT';
      const [teaching, availability, equipment] = await Promise.all([
        isDraft ? Promise.resolve(null) : getJson(`/api/teaching?termId=${encodeURIComponent(termId)}`),
        getJson(`/api/terms/${encodeURIComponent(termId)}/availability/me`),
        getJson('/api/equipment'),
      ]);
      if (thisRequest !== requestNumber) return;
      messages.replaceChildren();

      const panels = [];
      if (teaching) {
        panels.push(el('div', { class: 'two-col' }, schedulePanel(teaching, user.id), workloadPanel(teaching)));
      } else {
        panels.push(el('section', { class: 'panel' }, el('div', { class: 'empty' },
          el('strong', {}, 'Teaching schedule under construction'),
          'Course assignments and workload will appear here once the term is published.')));
      }
      panels.push(availabilityPanel(termId, availability, equipment.map((item) => item.itemType), savedMessage,
        (message) => load(termId, message)));

      body.replaceChildren(...panels);
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
    termSelect.replaceChildren(...[...terms].sort((a, b) => b.startDate.localeCompare(a.startDate))
      .map((term) => el('option', { value: term.id }, term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name)));
    const term = terms.find((item) => item.id === chosenTermId()) ?? defaultTerm(terms, today()) ?? terms[0];
    showTerm(term);
    load(term.id);
  }

  start();
  return el('div', { class: 'content' }, messages, body);
}
