import { chosenTermId, getJson, openSectionNext, patch, post, rememberTerm } from './api.js';
import { dateTime } from './dates.js';
import { banner, el } from './dom.js';
import { field, openForm, select } from './forms.js';
import { meetingLabel } from './timetable.js';

const TYPES = [
  ['ROOM_DOUBLE_BOOKING', 'Room double-booking'],
  ['INSTRUCTOR_OVERLAP', 'Instructor overlap'],
  ['INSTRUCTOR_UNAVAILABLE', 'Instructor unavailable'],
  ['EQUIPMENT_DOUBLE_BOOKING', 'Equipment double-booking'],
  ['CAPACITY_EXCEEDED', 'Capacity exceeded'],
  ['STUDENT_GROUP_CLASH', 'Student-group clash'],
];
const SEVERITIES = [['HIGH', 'High'], ['MEDIUM', 'Medium'], ['LOW', 'Low']];
const STATES = [['OPEN', 'Open'], ['DISMISSED', 'Dismissed'], ['RESOLVED', 'Resolved'], ['', 'All']];
// Changing the room or the instructors can fix these; the others need different times, so they go to the timetable.
const REASSIGNABLE = new Set(['ROOM_DOUBLE_BOOKING', 'CAPACITY_EXCEEDED', 'INSTRUCTOR_OVERLAP', 'INSTRUCTOR_UNAVAILABLE']);

const label = (pairs, value) => pairs.find(([key]) => key === value)?.[1] ?? value;
const termLabel = (term) => (term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name);
const sectionName = (section) => `${section.courseCode} · ${section.sectionNumber}`;
const countLabel = (count, one, many) => `${count} ${count === 1 ? one : many}`;

function bySeverity(a, b) {
  const rank = (conflict) => SEVERITIES.findIndex(([value]) => value === conflict.severity);
  return rank(a) - rank(b) || a.type.localeCompare(b.type) || a.detectedAt.localeCompare(b.detectedAt);
}

/** The Scheduler's list of what detection flagged in a term, with ways to fix or dismiss each one (UC-05). */
export function conflictsScreen(user, setTopbar) {
  const state = { terms: [], term: null, conflicts: [], sections: [], dismissals: [], rooms: [], instructors: [] };
  const filters = { status: 'OPEN', type: '', severity: '' };
  const messages = el('div', { class: 'messages' });
  const status = el('p', { class: 'status', role: 'status' });
  const list = el('div', { 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading conflicts…'));
  const meta = el('span', { class: 'meta' });
  const heading = el('h2', {}, 'Conflicts');
  const termSelect = el('select', { 'aria-label': 'Term', onchange: () => selectTerm(termSelect.value) });
  const termTag = el('span', { class: 'tag' });
  const rescanButton = el('button', { class: 'btn', type: 'button', onclick: rescan }, 'Re-scan schedule');
  const logSummary = el('summary', {}, 'Dismissal log');
  const logBody = el('div');
  const logPanel = el('details', { class: 'panel log', hidden: true }, logSummary, logBody);
  let requestNumber = 0;

  const filterSelect = (key, labelText, options) => {
    const control = select(`filter-${key}`, options, filters[key], {
      id: `conflict-${key}`,
      onchange: () => { filters[key] = control.value; showList(); },
    });
    return field(labelText, control);
  };
  const toolbar = el('div', { class: 'toolbar' },
    filterSelect('status', 'Show', STATES),
    filterSelect('type', 'Type', [['', 'All types'], ...TYPES]),
    filterSelect('severity', 'Severity', [['', 'All severities'], ...SEVERITIES]));

  async function start() {
    try {
      const [terms, rooms, instructors] = await Promise.all(['/api/terms', '/api/rooms', '/api/instructors'].map(getJson));
      Object.assign(state, { terms, rooms, instructors });
    } catch (error) {
      list.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => { messages.replaceChildren(); start(); }));
      return;
    }
    if (!state.terms.length) {
      list.replaceChildren(el('div', { class: 'empty' }, el('strong', {}, 'No terms yet'),
        el('a', { class: 'btn primary', href: '#timetable' }, 'Create a term in Timetable')));
      toolbar.hidden = true;
      return;
    }
    termSelect.replaceChildren(...[...state.terms].sort((a, b) => b.startDate.localeCompare(a.startDate))
      .map((term) => el('option', { value: term.id }, termLabel(term))));
    const chosen = state.terms.find((term) => term.id === chosenTermId()) ?? state.terms[0];
    await load(chosen.id);
  }

  function selectTerm(id) {
    rememberTerm(id);
    status.textContent = '';
    load(id);
  }

  /** The term, its conflicts and its sections arrive together, so a card never names sections from another term. */
  async function load(termId, conflictsAlready) {
    const thisRequest = ++requestNumber;
    list.setAttribute('aria-busy', 'true');
    try {
      const path = `/api/terms/${encodeURIComponent(termId)}`;
      const [conflicts, sections, dismissals] = await Promise.all([conflictsAlready ?? getJson(`${path}/conflicts`),
        getJson(`${path}/sections`), getJson(`${path}/dismissals`)]);
      if (thisRequest !== requestNumber) return;
      Object.assign(state, { term: state.terms.find((term) => term.id === termId), conflicts, sections, dismissals });
      messages.replaceChildren();
      showTerm();
      showList();
    } catch (error) {
      if (thisRequest !== requestNumber) return;
      list.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => load(termId)));
    } finally {
      if (thisRequest === requestNumber) list.removeAttribute('aria-busy');
    }
  }

  function showTerm() {
    const draft = state.term.status === 'DRAFT';
    termTag.className = draft ? 'tag draft' : 'tag published';
    termTag.textContent = draft ? 'Draft' : 'Published';
    termSelect.value = state.term.id;
    heading.textContent = state.term.name;
    setTopbar([termSelect, termTag], [rescanButton]);
  }

  /** The audit log, collapsed: it is a record to consult, not something to act on. */
  function showLog() {
    const { dismissals } = state;
    logPanel.hidden = false;
    logSummary.textContent = `Dismissal log (${dismissals.length})`;
    logBody.replaceChildren(dismissals.length
      ? el('table', { class: 'cards' },
        el('thead', {}, el('tr', {}, ['When', 'Conflict', 'Dismissed by', 'Reason'].map((name) => el('th', { scope: 'col' }, name)))),
        el('tbody', {}, dismissals.map((entry) => el('tr', {},
          el('td', { class: 'nowrap', 'data-label': 'When' }, dateTime(entry.dismissedAt)),
          el('td', { 'data-label': 'Conflict' }, el('div', {},
            el('div', {}, label(TYPES, entry.conflictType)),
            el('div', { class: 'muted' }, entry.explanation))),
          el('td', { 'data-label': 'Dismissed by' }, entry.dismissedByName),
          el('td', { 'data-label': 'Reason' }, entry.reason)))))
      : el('p', { class: 'panel-body muted' }, `Nothing has been dismissed in ${state.term.name}.`));
  }

  function showList() {
    showLog();
    const open = state.conflicts.filter((conflict) => conflict.resolutionState === 'OPEN');
    const openHigh = open.filter((conflict) => conflict.severity === 'HIGH').length;
    meta.textContent = `${countLabel(open.length, 'open conflict', 'open conflicts')}${openHigh ? `, ${openHigh} High` : ''}`;
    const shown = state.conflicts
      .filter((conflict) => !filters.status || conflict.resolutionState === filters.status)
      .filter((conflict) => !filters.type || conflict.type === filters.type)
      .filter((conflict) => !filters.severity || conflict.severity === filters.severity)
      .sort(bySeverity);
    if (shown.length) {
      list.replaceChildren(el('ul', { class: 'conflict-list' }, shown.map(conflictItem)));
      return;
    }
    const filtered = filters.type || filters.severity || filters.status !== 'OPEN';
    list.replaceChildren(el('div', { class: 'empty' },
      el('strong', {}, filtered ? 'Nothing matches these filters' : `No open conflicts in ${state.term.name}`),
      filtered
        ? el('button', { class: 'btn', type: 'button', onclick: clearFilters }, 'Show open conflicts')
        : 'Every change is checked as it is saved. Re-scan after a batch of changes to be sure.'));
  }

  function clearFilters() {
    Object.assign(filters, { status: 'OPEN', type: '', severity: '' });
    toolbar.querySelectorAll('select').forEach((control) => { control.value = filters[control.id.replace('conflict-', '')]; });
    showList();
  }

  function openIds() {
    return state.conflicts.filter((conflict) => conflict.resolutionState === 'OPEN').map((conflict) => conflict.id);
  }

  function affected(conflict) {
    return conflict.sectionIds.map((id) => state.sections.find((section) => section.id === id)).filter(Boolean);
  }

  function conflictItem(conflict) {
    const open = conflict.resolutionState === 'OPEN';
    const sections = affected(conflict);
    // The log is newest first, so the first entry is the dismissal that stands
    const dismissal = conflict.resolutionState === 'DISMISSED'
      && state.dismissals.find((entry) => entry.conflictId === conflict.id);
    const when = open ? `Found ${dateTime(conflict.detectedAt)}`
      : conflict.resolutionState === 'RESOLVED' && conflict.resolvedAt ? `Resolved ${dateTime(conflict.resolvedAt)}` : '';
    return el('li', { class: 'conflict' },
      el('div', { class: 'conflict-head' },
        el('span', { class: `tag sev-${conflict.severity.toLowerCase()}` }, label(SEVERITIES, conflict.severity)),
        el('h3', {}, label(TYPES, conflict.type)),
        !open && el('span', { class: conflict.resolutionState === 'RESOLVED' ? 'tag ok' : 'tag dismissed' }, label(STATES, conflict.resolutionState)),
        when && el('span', { class: 'meta' }, when)),
      el('p', {}, conflict.explanation),
      dismissal && el('p', { class: 'muted' },
        `Dismissed by ${dismissal.dismissedByName}, ${dateTime(dismissal.dismissedAt)}: “${dismissal.reason}”`),
      sections.length > 0 && el('div', { class: 'affected' },
        el('span', { class: 'muted' }, sections.length === 1 ? 'Section' : 'Sections'),
        sections.map((section) => el('button', {
          class: 'btn small',
          type: 'button',
          title: `${section.roomName ?? 'No room'}, ${section.meetings.map(meetingLabel).join(', ')}`,
          'aria-label': `Open ${section.courseCode} section ${section.sectionNumber} in the timetable`,
          onclick: () => openInTimetable(section),
        }, sectionName(section), el('span', { class: 'muted' }, ' · Open in timetable')))),
      open && el('div', { class: 'conflict-actions' },
        REASSIGNABLE.has(conflict.type) && sections.length > 0
          && el('button', { class: 'btn small primary', type: 'button', onclick: () => reassign(conflict, sections) }, 'Reassign'),
        el('button', { class: 'btn small quiet', type: 'button', onclick: () => dismiss(conflict) }, 'Dismiss')));
  }

  function openInTimetable(section) {
    openSectionNext(state.term.id, section.id);
    location.hash = '#timetable';
  }

  /**
   * Changes a section's room or instructors through the timetable's own endpoint, so the same department checks,
   * conflict re-check and (in a Published term) notices apply as for an edit made on the Timetable screen.
   */
  function reassign(conflict, sections) {
    const instructorsFirst = conflict.type === 'INSTRUCTOR_OVERLAP' || conflict.type === 'INSTRUCTOR_UNAVAILABLE';
    const sectionPick = select('sectionId', sections.map((section) => [section.id, sectionName(section)]), sections.at(-1).id, {
      onchange: () => fillFor(sections.find((section) => section.id === sectionPick.value)),
    });
    const roomPick = select('roomId', [], '');
    const checks = el('div', { class: 'checks' });
    const fillFor = (section) => {
      const rooms = state.rooms.filter((room) => room.status === 'AVAILABLE' || room.id === section.roomId);
      roomPick.replaceChildren(...rooms.map((room) => el('option', { value: room.id, selected: room.id === section.roomId },
        `${room.name} (${room.capacity} seats)${room.id === section.roomId ? ', current' : ''}`)));
      const chosen = new Set(section.instructors.map((person) => person.id));
      checks.replaceChildren(...state.instructors.map((person) => el('label', { class: 'check' },
        el('input', { type: 'checkbox', name: 'instructor', value: person.id, checked: chosen.has(person.id) }), person.name)));
    };
    fillFor(sections.at(-1));
    const roomField = field('Room', roomPick);
    const instructorField = el('fieldset', { class: 'group' }, el('legend', {}, 'Instructors'), checks);

    openForm({
      title: `Reassign: ${label(TYPES, conflict.type)}`,
      note: state.term.status === 'PUBLISHED'
        ? 'This term is published, so a change sends a notice to those affected. To change times, open the section in the timetable.'
        : 'To change times, open the section in the timetable instead.',
      submitLabel: 'Save and re-check',
      body: [
        sections.length > 1 ? field('Section to change', sectionPick) : el('p', {}, el('strong', {}, sectionName(sections[0]))),
        ...(instructorsFirst ? [instructorField, roomField] : [roomField, instructorField]),
      ],
      onSubmit: async (form) => {
        const sectionId = sections.length > 1 ? form.get('sectionId') : sections[0].id;
        const section = sections.find((item) => item.id === sectionId);
        const instructorIds = form.getAll('instructor');
        if (!instructorIds.length) throw new Error('Choose at least one instructor.');
        await patch(`/api/sections/${encodeURIComponent(sectionId)}/assignment`, { roomId: form.get('roomId'), instructorIds });
        const openBefore = new Set(openIds());
        await load(state.term.id);
        const now = state.conflicts.find((item) => item.id === conflict.id);
        // A fix can cause a new problem, e.g. a free room that is too small, so say so rather than only "resolved".
        const caused = openIds().filter((id) => !openBefore.has(id)).length;
        status.textContent = (now?.resolutionState === 'RESOLVED'
          ? `${sectionName(section)} saved. This conflict is resolved`
          : `${sectionName(section)} saved, but this conflict is still open`)
          + (caused ? `, and the change caused ${countLabel(caused, 'new conflict', 'new conflicts')}.` : '.');
      },
    });
  }

  function dismiss(conflict) {
    openForm({
      title: `Dismiss: ${label(TYPES, conflict.type)}`,
      note: 'Dismiss only if this isn’t a real problem. It stays dismissed when the schedule is re-scanned, and your name, the time and the reason are kept in the audit log.',
      submitLabel: 'Dismiss conflict',
      body: [
        el('p', {}, conflict.explanation),
        field('Reason', el('textarea', { name: 'reason', required: true, maxlength: 500, rows: 3,
          placeholder: 'For example: team-taught, both instructors agreed' })),
      ],
      onSubmit: async (form) => {
        const reason = form.get('reason').trim();
        if (!reason) throw new Error('Give a reason for dismissing this conflict.');
        await post(`/api/conflicts/${encodeURIComponent(conflict.id)}/dismiss`, { reason });
        await load(state.term.id);
        status.textContent = `${label(TYPES, conflict.type)} dismissed.`;
      },
    });
  }

  async function rescan() {
    if (!state.term) return;
    rescanButton.disabled = true;
    const before = state.conflicts.filter((conflict) => conflict.resolutionState === 'OPEN').length;
    try {
      const conflicts = await post(`/api/terms/${encodeURIComponent(state.term.id)}/rescan`);
      await load(state.term.id, conflicts);
      const after = state.conflicts.filter((conflict) => conflict.resolutionState === 'OPEN').length;
      status.textContent = `Re-scanned ${state.term.name}: ${countLabel(after, 'open conflict', 'open conflicts')}`
        + (after === before ? '.' : ` (was ${before}).`);
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    } finally {
      rescanButton.disabled = false;
    }
  }

  start();
  return el('div', { class: 'content' },
    messages,
    el('section', { class: 'panel' },
      el('div', { class: 'panel-head' }, heading, meta),
      toolbar,
      status,
      list),
    logPanel);
}
