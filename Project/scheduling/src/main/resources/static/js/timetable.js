import { chosenTermId, del, getJson, patch, post, put, rememberTerm } from './api.js';
import { dateRange, shortDate, timeRange } from './dates.js';
import { banner, el } from './dom.js';
import { field, input, openForm, select } from './forms.js';

const DAYS = [['MONDAY', 'Monday'], ['TUESDAY', 'Tuesday'], ['WEDNESDAY', 'Wednesday'], ['THURSDAY', 'Thursday'],
  ['FRIDAY', 'Friday'], ['SATURDAY', 'Saturday'], ['SUNDAY', 'Sunday']];
const PATTERNS = [['WEEKLY', 'Weekly'], ['ONE_TIME', 'One time'], ['EXAM', 'Exam']];

const hhmm = (time) => time.slice(0, 5);
const termLabel = (term) => (term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name);
const countLabel = (sections) => (sections === 1 ? '1 section' : `${sections} sections`);

function meetingLabel(meeting) {
  const when = timeRange(meeting.start, meeting.end);
  if (meeting.pattern === 'WEEKLY') {
    return `${DAYS.find(([value]) => value === meeting.day)[1].slice(0, 3)} ${when}`;
  }
  return `${meeting.pattern === 'EXAM' ? 'Exam, ' : ''}${shortDate(meeting.date)}, ${when}`;
}

/** One editable row of a section's meetings. read() gives the API's meeting object. */
function meetingRow(meeting, term, onRemove) {
  const pattern = select('pattern', PATTERNS, meeting.pattern, { onchange: showDayOrDate });
  const day = select('day', DAYS, meeting.day ?? 'MONDAY');
  const date = input('date', { type: 'date', min: term.startDate, max: term.endDate, value: meeting.date ?? null });
  const start = input('start', { type: 'time', required: true, value: hhmm(meeting.start) });
  const end = input('end', { type: 'time', required: true, value: hhmm(meeting.end) });
  const dayField = field('Day', day);
  const dateField = field('Date', date);
  const row = el('div', { class: 'meeting-row' },
    field('Repeats', pattern), dayField, dateField, field('Start', start), field('End', end),
    el('button', { class: 'btn quiet small', type: 'button', onclick: () => onRemove(entry) }, 'Remove'));

  function showDayOrDate() {
    const weekly = pattern.value === 'WEEKLY';
    dayField.hidden = !weekly;
    dateField.hidden = weekly;
    date.required = !weekly;
  }
  showDayOrDate();

  const entry = {
    row,
    read: () => {
      const weekly = pattern.value === 'WEEKLY';
      return {
        pattern: pattern.value,
        day: weekly ? day.value : null,
        date: weekly ? null : date.value,
        start: start.value,
        end: end.value,
      };
    },
  };
  return entry;
}

/** The Scheduler's builder: terms, the sections in a term, and publishing. */
export function timetableScreen(user, setTopbar) {
  const state = { terms: [], term: null, sections: [], subjects: [], rooms: [], equipment: [], instructors: [] };
  const messages = el('div', { class: 'messages' });
  const panel = el('section', { class: 'panel' }, el('div', { class: 'loading' }, 'Loading timetable…'));
  let topbar = null;
  let requestNumber = 0;

  const showError = (error, retry) => messages.replaceChildren(banner(error.message, retry && 'Try again', retry));

  async function start() {
    try {
      const [terms, subjects, rooms, equipment, instructors] = await Promise.all(
        ['/api/terms', '/api/subjects', '/api/rooms', '/api/equipment', '/api/instructors'].map(getJson));
      Object.assign(state, { terms, subjects, rooms, equipment, instructors });
      messages.replaceChildren();
      const chosen = terms.find((term) => term.id === chosenTermId());
      await selectTerm((chosen ?? terms[0])?.id, false); // opening the screen is not choosing a term
    } catch (error) {
      panel.replaceChildren();
      showError(error, start);
    }
  }

  /**
   * The current term changes only once the new sections have arrived, so the term, its sections and every action on
   * them always agree. An answer overtaken by a newer choice, whether it worked or failed, is ignored.
   */
  async function selectTerm(id, remember = true) {
    const thisRequest = ++requestNumber;
    const term = state.terms.find((item) => item.id === id) ?? null;
    let sections;
    try {
      sections = term ? await getJson(`/api/terms/${encodeURIComponent(term.id)}/sections`) : [];
    } catch (error) {
      if (thisRequest === requestNumber) throw error;
      return;
    }
    if (thisRequest !== requestNumber) return;
    Object.assign(state, { term, sections });
    if (remember && term) rememberTerm(term.id);
    setSwitching(false);
    render();
  }

  /** Changing term with the picker. While it loads, nothing can act on the term being left. */
  async function switchTerm(id) {
    messages.replaceChildren();
    setSwitching(true);
    try {
      await selectTerm(id);
    } catch (error) {
      setSwitching(false);
      topbar.picker.value = state.term.id;
      showError(error, () => {
        topbar.picker.value = id;
        switchTerm(id);
      });
    }
  }

  function setSwitching(switching) {
    panel.inert = switching;
    topbar?.details.forEach((item) => { item.hidden = switching; });
    topbar?.actions.forEach((button) => { button.disabled = switching; });
  }

  function render() {
    renderTopbar();
    panel.replaceChildren(...(state.term ? sectionsPanel() : [el('div', { class: 'empty' },
      el('strong', {}, 'No terms yet'),
      'A term holds the sections you schedule, such as “Fall 2026”.',
      el('button', { class: 'btn primary', type: 'button', onclick: newTerm }, 'Create the first term'))]));
  }

  function renderTopbar() {
    const newButton = el('button', { class: 'btn', type: 'button', onclick: newTerm }, 'New term');
    const { term } = state;
    if (!term) {
      topbar = null;
      setTopbar([], [newButton]);
      return;
    }
    const draft = term.status === 'DRAFT';
    const picker = select('term', state.terms.map((item) => [item.id, termLabel(item)]), term.id, {
      'aria-label': 'Term',
      onchange: () => switchTerm(picker.value),
    });
    const details = [el('span', { class: draft ? 'tag draft' : 'tag published' }, draft ? 'Draft' : 'Published'),
      el('span', { class: 'muted' }, dateRange(term.startDate, term.endDate))];
    const actions = [newButton,
      el('button', { class: 'btn', type: 'button', onclick: editTerm }, 'Edit term'),
      el('button', { class: 'btn', type: 'button', onclick: copyTerm }, 'Copy term'),
      draft && el('button', { class: 'btn danger-line', type: 'button', onclick: deleteTerm }, 'Delete term'),
      draft && el('button', { class: 'btn primary', type: 'button', onclick: publishTerm }, 'Publish term')].filter(Boolean);
    topbar = { picker, details, actions };
    setTopbar([picker, ...details], actions);
  }

  function sectionsPanel() {
    const missing = [!state.subjects.length && 'subject', !state.rooms.length && 'room'].filter(Boolean);
    const count = countLabel(state.sections.length);
    const head = el('div', { class: 'panel-head' },
      el('h2', {}, 'Sections'),
      el('div', { class: 'crumb' },
        el('span', { class: 'meta' }, count),
        !missing.length && el('button', { class: 'btn small', type: 'button', onclick: () => sectionForm() }, 'Add section')));
    if (missing.length) {
      return [head, el('div', { class: 'empty' },
        el('strong', {}, `Add at least one ${missing.join(' and one ')} first`),
        'A section needs a subject and a room from the catalog.',
        el('a', { class: 'btn primary', href: '#catalog' }, 'Go to Catalog'))];
    }
    if (!state.sections.length) {
      return [head, el('div', { class: 'empty' },
        el('strong', {}, 'No sections in this term yet'),
        el('button', { class: 'btn primary', type: 'button', onclick: () => sectionForm() }, 'Add the first section'))];
    }
    const noRoom = sectionsWithoutRoom();
    return [head, noRoom > 0 && el('div', { class: 'panel-body' }, banner(
      `${countLabel(noRoom)} ${noRoom === 1 ? 'has' : 'have'} no room, usually because a copied room is out of service. Use Edit to choose one.`)),
    el('div', { class: 'scroll-x' }, el('table', {},
      el('thead', {}, el('tr', {}, ['Course', 'Section', 'Room', 'Instructors', 'Meets', ''].map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, state.sections.map(sectionRow))))].filter(Boolean);
  }

  function sectionsWithoutRoom() {
    return state.sections.filter((section) => !section.roomId).length;
  }

  function sectionRow(section) {
    const label = `${section.courseCode} section ${section.sectionNumber}`;
    return el('tr', {},
      el('td', {}, section.courseCode),
      el('td', {}, section.sectionNumber),
      el('td', {}, section.roomName ?? el('span', { class: 'tag cancelled' }, 'No room')),
      el('td', {}, section.instructors.map((person) => person.name).join(', ')),
      el('td', {}, section.meetings.map((meeting) => el('div', { class: 'nowrap' }, meetingLabel(meeting)))),
      el('td', { class: 'row-actions' },
        el('button', { class: 'btn small', type: 'button', 'aria-label': `Edit ${label}`, onclick: () => sectionForm(section) }, 'Edit'),
        el('button', { class: 'btn quiet small', type: 'button', 'aria-label': `Remove ${label}`, onclick: () => removeSection(section) }, 'Remove')));
  }

  function newTerm() {
    openForm({
      title: 'New term',
      note: 'A new term starts as a Draft, which students and instructors can’t see in their calendars.',
      submitLabel: 'Create term',
      body: [
        field('Name', input('name', { required: true, maxlength: 100, placeholder: 'Fall 2026' })),
        field('First day', input('startDate', { type: 'date', required: true })),
        field('Last day', input('endDate', { type: 'date', required: true })),
      ],
      onSubmit: async (form) => {
        const term = await post('/api/terms', {
          name: form.get('name').trim(), startDate: form.get('startDate'), endDate: form.get('endDate'),
        });
        state.terms = [term, ...state.terms].sort((a, b) => b.startDate.localeCompare(a.startDate));
        await selectTerm(term.id);
      },
    });
  }

  function editTerm() {
    const { term } = state;
    const published = term.status === 'PUBLISHED';
    openForm({
      title: `Edit ${term.name}`,
      note: published
        ? 'Students and instructors will see the new name. No notice is sent. The dates of a published term can’t be changed.'
        : 'One-time meetings and exams must stay inside the new dates.',
      submitLabel: 'Save term',
      body: [
        field('Name', input('name', { required: true, maxlength: 100, value: term.name })),
        field('First day', input('startDate', { type: 'date', required: true, value: term.startDate, disabled: published })),
        field('Last day', input('endDate', { type: 'date', required: true, value: term.endDate, disabled: published })),
      ],
      onSubmit: async (form) => {
        // Only what changed is sent, and the API saves it all or nothing.
        const changes = {};
        const name = form.get('name').trim();
        if (name !== term.name) changes.name = name;
        if (!published && form.get('startDate') !== term.startDate) changes.startDate = form.get('startDate');
        if (!published && form.get('endDate') !== term.endDate) changes.endDate = form.get('endDate');
        if (!Object.keys(changes).length) return;
        Object.assign(term, await patch(`/api/terms/${encodeURIComponent(term.id)}`, changes));
        state.terms.sort((a, b) => b.startDate.localeCompare(a.startDate));
        messages.replaceChildren();
        render();
      },
    });
  }

  function copyTerm() {
    const { term } = state;
    openForm({
      title: `Copy ${term.name}`,
      note: 'The copy is a new Draft with the same dates and sections, and this term stays as it is. A room that is now out of service is left empty for you to choose again.',
      submitLabel: 'Copy term',
      body: [field('Name of the copy', input('name', { required: true, maxlength: 100, value: `${term.name} (copy)` }))],
      onSubmit: async (form) => {
        const copy = await post(`/api/terms/${encodeURIComponent(term.id)}/copy`, { name: form.get('name').trim() });
        state.terms = [copy, ...state.terms].sort((a, b) => b.startDate.localeCompare(a.startDate));
        messages.replaceChildren();
        await selectTerm(copy.id);
      },
    });
  }

  async function publishTerm() {
    const { term } = state;
    const noRoom = sectionsWithoutRoom();
    const warning = noRoom > 0 ? `${countLabel(noRoom)} still ${noRoom === 1 ? 'has' : 'have'} no room. ` : '';
    if (!confirm(`${warning}Publish ${term.name}? Students and instructors will see it in their calendars, and it can no longer be deleted.`)) return;
    const path = `/api/terms/${encodeURIComponent(term.id)}/publish`;
    let published;
    try {
      published = await post(path);
    } catch (error) {
      // Open High-severity conflicts answer 409 and need a second, explicit yes (FR4.7).
      if (error.status !== 409 || !confirm(error.message)) {
        if (error.status !== 409) showError(error);
        return;
      }
      try {
        published = await post(`${path}?confirm=true`);
      } catch (retryError) {
        showError(retryError);
        return;
      }
    }
    messages.replaceChildren();
    Object.assign(term, published);
    render();
  }

  async function deleteTerm() {
    const { term, sections } = state;
    if (!confirm(`Delete the draft ${term.name} and its ${countLabel(sections.length)}? This can’t be undone.`)) return;
    try {
      await del(`/api/terms/${encodeURIComponent(term.id)}`);
      messages.replaceChildren();
      state.terms = state.terms.filter((item) => item.id !== term.id);
      await selectTerm(state.terms[0]?.id);
    } catch (error) {
      showError(error);
    }
  }

  async function removeSection(section) {
    const { term } = state;
    const warning = term.status === 'PUBLISHED' ? ' Those affected will get a cancellation notice.' : '';
    if (!confirm(`Remove ${section.courseCode} section ${section.sectionNumber}?${warning}`)) return;
    try {
      await del(`/api/terms/${encodeURIComponent(term.id)}/sections/${encodeURIComponent(section.id)}`);
      messages.replaceChildren();
      await selectTerm(term.id);
    } catch (error) {
      showError(error);
    }
  }

  /** Adds a section, or edits one if given. Subject and number identify a section, so they can't be edited. */
  function sectionForm(section) {
    const { term } = state;
    const meetingsBox = el('div', { class: 'stack small' });
    const meetings = [];
    const addMeeting = (meeting) => {
      const entry = meetingRow(meeting, term, (removed) => {
        meetings.splice(meetings.indexOf(removed), 1);
        removed.row.remove();
      });
      meetings.push(entry);
      meetingsBox.append(entry.row);
    };
    (section?.meetings ?? [{ pattern: 'WEEKLY', day: 'MONDAY', start: '09:00', end: '09:50' }]).forEach(addMeeting);

    const rooms = state.rooms.filter((room) => room.status === 'AVAILABLE' || room.id === section?.roomId);
    const roomOptions = rooms.map((room) => [room.id, `${room.name} (${room.capacity} seats)`]);
    const needs = new Map((section?.equipment ?? []).map((need) => [need.equipmentId, need.units]));
    const chosen = new Set((section?.instructors ?? []).map((person) => person.id));

    openForm({
      title: section ? `Edit ${section.courseCode} section ${section.sectionNumber}` : `Add section to ${term.name}`,
      note: term.status === 'PUBLISHED' ? 'This term is published, so changing the room, times or instructors sends a notice to those affected.' : null,
      submitLabel: section ? 'Save changes' : 'Add section',
      body: [
        !section && field('Subject', select('subjectId', state.subjects.map((subject) => [subject.id, `${subject.courseCode}: ${subject.title}`]))),
        !section && field('Section number', input('sectionNumber', { required: true, maxlength: 10, value: '01' })),
        field('Room', select('roomId', [['', 'Choose a room'], ...roomOptions], section?.roomId ?? '', { required: true })),
        el('fieldset', { class: 'group' },
          el('legend', {}, 'Instructors'),
          el('div', { class: 'checks' }, state.instructors.map((person) => el('label', { class: 'check' },
            el('input', { type: 'checkbox', name: 'instructor', value: person.id, checked: chosen.has(person.id) }),
            person.name)))),
        el('fieldset', { class: 'group' },
          el('legend', {}, 'Meetings'),
          meetingsBox,
          el('button', {
            class: 'btn small',
            type: 'button',
            onclick: () => {
              const last = meetings.at(-1)?.read();
              addMeeting({ pattern: 'WEEKLY', day: last?.day ?? 'MONDAY', start: last?.start || '09:00', end: last?.end || '09:50' });
            },
          }, 'Add another meeting')),
        state.equipment.length > 0 && el('fieldset', { class: 'group' },
          el('legend', {}, 'Equipment units needed (optional)'),
          el('div', { class: 'checks' }, state.equipment.map((item) => el('label', { class: 'check' },
            el('input', { class: 'units', type: 'number', name: `units-${item.id}`, min: 0, value: needs.get(item.id) ?? 0 }),
            item.name)))),
      ].filter(Boolean),
      onSubmit: async (form) => {
        const body = {
          roomId: form.get('roomId'),
          instructorIds: form.getAll('instructor'),
          meetings: meetings.map((entry) => entry.read()),
          equipment: state.equipment
            .map((item) => ({ equipmentId: item.id, units: Number(form.get(`units-${item.id}`)) }))
            .filter((need) => need.units > 0),
        };
        const termPath = `/api/terms/${encodeURIComponent(term.id)}/sections`;
        if (section) {
          await put(`${termPath}/${encodeURIComponent(section.id)}`, { ...body, subjectId: section.subjectId, sectionNumber: section.sectionNumber });
        } else {
          await post(termPath, { ...body, subjectId: form.get('subjectId'), sectionNumber: form.get('sectionNumber') });
        }
        await selectTerm(term.id);
      },
    });
  }

  start();
  return el('div', { class: 'content' }, messages, panel);
}
