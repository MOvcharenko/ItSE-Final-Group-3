import { dateRange } from './dates.js';
import { banner, dataTable, el } from './dom.js';
import { field, input, openForm, select } from './forms.js';
import { latestOnly, Superseded } from './http.js';
import { DAYS, meetingLabel, plural } from './labels.js';
import { api } from './scheduling-api.js';
import { rememberTerm, takeSectionToOpen } from './session.js';
import { initialTerm, sortNewestFirst, termPicker } from './terms.js';
import { datedMeetings, weekGrid } from './timetable-grid.js';

const PATTERNS = [['WEEKLY', 'Weekly'], ['ONE_TIME', 'One time'], ['EXAM', 'Exam']];
const DEFAULT_MINUTES = 50; // a course dragged from the list starts as one 50-minute class

const hhmm = (time) => time.slice(0, 5);
const sectionName = (section) => `${section.courseCode} · ${section.sectionNumber}`;
const toMinutes = (time) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
const asSent = (meeting) => ({ ...meeting, start: hhmm(meeting.start), end: hhmm(meeting.end) });

/** The next free section number for a subject in this term: "01", then "02", and so on. */
function nextSectionNumber(sections, subjectId) {
  const used = sections.filter((section) => section.subjectId === subjectId)
    .map((section) => Number.parseInt(section.sectionNumber, 10)).filter(Number.isFinite);
  return String(used.length ? Math.max(...used) + 1 : 1).padStart(2, '0');
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
  const state = { terms: [], term: null, sections: [], conflicts: [], subjects: [], rooms: [], equipment: [], instructors: [] };
  // A phone is too narrow for a week grid, so it starts on the list.
  const view = { mode: matchMedia('(max-width: 760px)').matches ? 'list' : 'grid', busyInstructor: '', availabilityBlocks: [] };
  const messages = el('div', { class: 'messages' });
  const status = el('p', { class: 'status', role: 'status' });
  const panel = el('section', { class: 'panel' }, el('div', { class: 'loading' }, 'Loading timetable…'));
  // A term is remembered for the other screens only once its sections have loaded.
  const picker = termPicker((term) => switchTerm(term.id), { remember: false });
  let topbar = null;
  const fetchTerm = latestOnly((term) => (term
    ? Promise.all([api.sections(term.id), api.conflicts(term.id, 'OPEN')])
    : Promise.resolve([[], []])));
  // The "Show busy times for" lookup, so only the latest choice's answer is shown
  const fetchAvailability = latestOnly((instructorId, termId) => api.instructorAvailability(instructorId, termId));

  const showError = (error, retry) => messages.replaceChildren(banner(error.message, retry && 'Try again', retry));

  async function start() {
    try {
      const [terms, subjects, rooms, equipment, instructors] = await Promise.all(
        [api.terms(), api.subjects(), api.rooms(), api.equipment(), api.instructors()]);
      Object.assign(state, { terms, subjects, rooms, equipment, instructors });
      messages.replaceChildren();
      await selectTerm(initialTerm(terms)?.id, false); // opening the screen is not choosing a term
      // Arrived from "Open in timetable" on the Conflicts screen
      const openId = takeSectionToOpen();
      const toOpen = openId && state.sections.find((section) => section.id === openId);
      if (toOpen) sectionForm(toOpen);
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
    const term = state.terms.find((item) => item.id === id) ?? null;
    let sections;
    let conflicts;
    try {
      [sections, conflicts] = await fetchTerm(term);
    } catch (error) {
      if (error instanceof Superseded) return;
      throw error;
    }
    if (term?.id !== state.term?.id) {
      view.busyInstructor = '';
      view.availabilityBlocks = [];
      fetchAvailability.drop(); // an answer still on its way is for the term being left
    }
    Object.assign(state, { term, sections, conflicts });
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
      picker.show(state.term);
      showError(error, () => {
        picker.select.value = id;
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
    status.textContent = ''; // a message from the last action; moveMeeting sets its own after rendering
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
    picker.setTerms(state.terms);
    picker.show(term);
    const details = [picker.tag, el('span', { class: 'muted' }, dateRange(term.startDate, term.endDate))];
    const actions = [newButton,
      el('button', { class: 'btn', type: 'button', onclick: editTerm }, 'Edit term'),
      el('button', { class: 'btn', type: 'button', onclick: copyTerm }, 'Copy term'),
      draft && el('button', { class: 'btn danger-line', type: 'button', onclick: deleteTerm }, 'Delete term'),
      draft && el('button', { class: 'btn primary', type: 'button', onclick: publishTerm }, 'Publish term')].filter(Boolean);
    topbar = { details, actions };
    setTopbar([picker.select, ...details], actions);
  }

  function sectionsPanel() {
    const missing = [!state.subjects.length && 'subject', !state.rooms.length && 'room'].filter(Boolean);
    const open = state.conflicts.length;
    const modes = [['grid', 'Week grid'], ['list', 'List']].map(([mode, text]) => el('button', {
      type: 'button',
      'aria-pressed': String(view.mode === mode),
      onclick: () => { view.mode = mode; status.textContent = ''; render(); },
    }, text));
    const head = el('div', { class: 'panel-head' },
      el('h2', {}, 'Sections'),
      el('div', { class: 'crumb' },
        !missing.length && el('div', { class: 'segmented', role: 'group', 'aria-label': 'Show sections as' }, modes),
        open > 0 && el('a', { class: 'tag sev-high', href: '#conflicts' }, plural(open, 'open conflict')),
        el('span', { class: 'meta' }, plural(state.sections.length, 'section')),
        !missing.length && el('button', { class: 'btn small', type: 'button', onclick: () => sectionForm() }, 'Add section')));
    if (missing.length) {
      return [head, el('div', { class: 'empty' },
        el('strong', {}, `Add at least one ${missing.join(' and one ')} first`),
        'A section needs a subject and a room from the catalog.',
        el('a', { class: 'btn primary', href: '#catalog' }, 'Go to Catalog'))];
    }
    // On the grid, an empty term still shows the grid, so the first course can be dragged onto it.
    if (!state.sections.length && view.mode !== 'grid') {
      return [head, el('div', { class: 'empty' },
        el('strong', {}, 'No sections in this term yet'),
        el('button', { class: 'btn primary', type: 'button', onclick: () => sectionForm() }, 'Add the first section'))];
    }
    const noRoom = sectionsWithoutRoom();
    return [head, status, noRoom > 0 && el('div', { class: 'panel-body' }, banner(
      `${plural(noRoom, 'section')} ${noRoom === 1 ? 'has' : 'have'} no room, usually because a copied room is out of service. Use Edit to choose one.`)),
    ...(view.mode === 'grid' ? gridView() : [sectionsTable()])].filter(Boolean);
  }

  function sectionsTable() {
    return dataTable([
      { label: 'Course', cell: (section) => section.courseCode },
      { label: 'Section', cell: (section) => section.sectionNumber },
      { label: 'Room', cell: (section) => section.roomName ?? el('span', { class: 'tag cancelled' }, 'No room') },
      { label: 'Instructors', cell: (section) => section.instructors.map((person) => person.name).join(', ') },
      { label: 'Meets', cell: (section) => section.meetings.map((meeting) => el('div', { class: 'nowrap' }, meetingLabel(meeting))) },
      { label: '', class: 'row-actions', cell: sectionActions },
    ], state.sections);
  }

  function sectionActions(section) {
    const label = `${section.courseCode} section ${section.sectionNumber}`;
    return [
      el('button', { class: 'btn small', type: 'button', 'aria-label': `Edit ${label}`, onclick: () => sectionForm(section) }, 'Edit'),
      el('button', { class: 'btn quiet small', type: 'button', 'aria-label': `Remove ${label}`, onclick: () => removeSection(section) }, 'Remove'),
    ];
  }

  function gridView() {
    const conflictCounts = new Map();
    state.conflicts.forEach((conflict) => conflict.sectionIds.forEach((id) => conflictCounts.set(id, (conflictCounts.get(id) ?? 0) + 1)));
    const teaching = new Map();
    state.sections.forEach((section) => section.instructors.forEach((person) => teaching.set(person.id, person.name)));
    const busyPick = select('busy', [['', 'Nobody'], ...[...teaching].sort((a, b) => a[1].localeCompare(b[1]))], view.busyInstructor, {
      id: 'busy-instructor',
      onchange: () => showBusyTimes(busyPick.value),
    });
    const dated = datedMeetings(state.sections);
    const hint = state.sections.length
      ? 'Drag a course from the list onto a day and time to add a section, or a section to give it another weekly meeting. '
        + 'Drag a class on the grid to move it. While you drag, times when its instructors or room are already in use are shaded. Click anything to edit it.'
      : 'Drag a course from the list onto a day and time to add the first section, or click a course to fill in the form.';
    return [
      el('div', { class: 'toolbar' },
        state.sections.length > 0 && field('Show busy times for', busyPick),
        el('p', { class: 'hint' }, hint)),
      weekGrid({
        sections: state.sections,
        published: state.term.status === 'PUBLISHED',
        conflictCounts,
        busyInstructor: view.busyInstructor,
        availabilityBlocks: view.availabilityBlocks,
        onOpen: (section) => sectionForm(section),
        onMove: moveMeeting,
        palette: paletteGroups(),
      }),
      dated.length > 0 && el('div', { class: 'panel-body dated' },
        el('h3', {}, 'One-time meetings and exams'),
        el('ul', {}, dated.map(({ section, meeting }) => el('li', {},
          el('button', { class: 'btn small quiet', type: 'button', onclick: () => sectionForm(section) }, sectionName(section)),
          ` ${meetingLabel(meeting)}`)))),
    ].filter(Boolean);
  }

  /** Shades an instructor's teaching and declared availability on the grid, or nobody's. */
  async function showBusyTimes(instructorId) {
    messages.replaceChildren(); // an earlier lookup's error no longer applies
    view.busyInstructor = instructorId;
    view.availabilityBlocks = [];
    let failure = null;
    if (instructorId && state.term) {
      try {
        view.availabilityBlocks = (await fetchAvailability(instructorId, state.term.id)).blocks || [];
      } catch (error) {
        if (error instanceof Superseded) return; // a newer choice has taken over
        failure = error;
      }
    } else {
      fetchAvailability.drop(); // "Nobody": a lookup still on its way no longer applies
    }
    render();
    // Their teaching is still shaded; only the declared availability couldn't be shown.
    if (failure) showError(failure);
  }

  /**
   * The list beside the grid. A course dropped on the grid opens the Add section form with that time filled in,
   * since a new section still needs a room and instructors; a section dropped on it gets another weekly meeting.
   */
  function paletteGroups() {
    const courses = [...state.subjects].sort((a, b) => a.courseCode.localeCompare(b.courseCode)).map((subject) => ({
      label: subject.courseCode,
      detail: subject.title,
      duration: DEFAULT_MINUTES,
      onClick: () => sectionForm(null, { subjectId: subject.id }),
      onDrop: (spot) => sectionForm(null, { subjectId: subject.id, meeting: { pattern: 'WEEKLY', ...spot } }),
    }));
    const sections = [...state.sections].sort((a, b) => sectionName(a).localeCompare(sectionName(b))).map((section) => {
      const weekly = section.meetings.find((meeting) => meeting.pattern === 'WEEKLY');
      return {
        label: sectionName(section),
        detail: plural(section.meetings.length, 'meeting'),
        duration: weekly ? toMinutes(weekly.end) - toMinutes(weekly.start) : DEFAULT_MINUTES,
        section,
        onClick: () => sectionForm(section),
        onDrop: (spot) => addMeeting(section, spot),
      };
    });
    return [
      { heading: 'Courses: drag to add a section', items: courses },
      { heading: 'Sections: drag to add a meeting', items: sections },
    ];
  }

  /** Saves a drag on the grid that moves one of a section's weekly meetings. */
  function moveMeeting(section, index, to) {
    const moved = { pattern: 'WEEKLY', day: to.day, start: to.start, end: to.end };
    const meetings = section.meetings.map((meeting, i) => (i === index ? moved : asSent(meeting)));
    saveMeetings(section, meetings, `Move ${sectionName(section)} to ${meetingLabel(moved)}?`,
      `${sectionName(section)} moved to ${meetingLabel(moved)}.`);
  }

  /** Saves a section dropped from the list: it keeps its meetings and gets one more weekly meeting. */
  function addMeeting(section, to) {
    const added = { pattern: 'WEEKLY', day: to.day, start: to.start, end: to.end };
    const same = section.meetings.some((meeting) => meeting.pattern === 'WEEKLY' && meeting.day === added.day
      && hhmm(meeting.start) === added.start && hhmm(meeting.end) === added.end);
    if (same) {
      status.textContent = `${sectionName(section)} already meets ${meetingLabel(added)}.`;
      return;
    }
    saveMeetings(section, [...section.meetings.map(asSent), added], `Add a meeting to ${sectionName(section)} on ${meetingLabel(added)}?`,
      `${sectionName(section)} now also meets ${meetingLabel(added)}.`);
  }

  /**
   * Puts the section back whole with the new meetings, so the server checks it like any other edit and re-runs
   * conflict detection. The answer says if the change caused a conflict.
   */
  async function saveMeetings(section, meetings, question, done) {
    const { term } = state;
    if (term.status === 'PUBLISHED' && !confirm(`${question} This term is published, so those affected get a notice.`)) {
      render(); // put the block back
      return;
    }
    const openBefore = new Set(state.conflicts.map((conflict) => conflict.id));
    try {
      await api.saveSection(term.id, section.id, {
        subjectId: section.subjectId, sectionNumber: section.sectionNumber, roomId: section.roomId,
        instructorIds: section.instructors.map((person) => person.id), meetings, equipment: section.equipment,
      });
      messages.replaceChildren();
      await selectTerm(term.id);
      const caused = state.conflicts.filter((conflict) => !openBefore.has(conflict.id)).length;
      status.textContent = done + (caused ? ` This caused ${plural(caused, 'new conflict')}; see Conflicts.` : '');
    } catch (error) {
      render();
      showError(error);
    }
  }

  function sectionsWithoutRoom() {
    return state.sections.filter((section) => !section.roomId).length;
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
        const term = await api.createTerm({
          name: form.get('name').trim(), startDate: form.get('startDate'), endDate: form.get('endDate'),
        });
        state.terms = sortNewestFirst([term, ...state.terms]);
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
        Object.assign(term, await api.updateTerm(term.id, changes));
        state.terms = sortNewestFirst(state.terms);
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
        const copy = await api.copyTerm(term.id, form.get('name').trim());
        state.terms = sortNewestFirst([copy, ...state.terms]);
        messages.replaceChildren();
        await selectTerm(copy.id);
      },
    });
  }

  async function publishTerm() {
    const { term } = state;
    const noRoom = sectionsWithoutRoom();
    const warning = noRoom > 0 ? `${plural(noRoom, 'section')} still ${noRoom === 1 ? 'has' : 'have'} no room. ` : '';
    if (!confirm(`${warning}Publish ${term.name}? Students and instructors will see it in their calendars, and it can no longer be deleted.`)) return;
    let published;
    try {
      published = await api.publishTerm(term.id);
    } catch (error) {
      // Open High-severity conflicts answer 409 and need a second, explicit yes (FR4.7).
      if (error.status !== 409 || !confirm(error.message)) {
        if (error.status !== 409) showError(error);
        return;
      }
      try {
        published = await api.publishTerm(term.id, true);
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
    if (!confirm(`Delete the draft ${term.name} and its ${plural(sections.length, 'section')}? This can’t be undone.`)) return;
    try {
      await api.deleteTerm(term.id);
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
      await api.removeSection(term.id, section.id);
      messages.replaceChildren();
      await selectTerm(term.id);
    } catch (error) {
      showError(error);
    }
  }

  /**
   * Adds a section, or edits one if given. Subject and number identify a section, so they can't be edited.
   * preset fills in a new section: { subjectId, meeting } from a course dragged onto the grid.
   */
  function sectionForm(section, preset = {}) {
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
    (section?.meetings ?? [preset.meeting ?? { pattern: 'WEEKLY', day: 'MONDAY', start: '09:00', end: '09:50' }]).forEach(addMeeting);
    const subjectId = preset.subjectId ?? state.subjects[0]?.id;
    const numberInput = input('sectionNumber', { required: true, maxlength: 10, value: nextSectionNumber(state.sections, subjectId) });
    const subjectSelect = select('subjectId', state.subjects.map((subject) => [subject.id, `${subject.courseCode}: ${subject.title}`]), subjectId, {
      onchange: () => { numberInput.value = nextSectionNumber(state.sections, subjectSelect.value); },
    });

    const rooms = state.rooms.filter((room) => room.status === 'AVAILABLE' || room.id === section?.roomId);
    const roomOptions = rooms.map((room) => [room.id, `${room.name} (${room.capacity} seats)`]);
    const needs = new Map((section?.equipment ?? []).map((need) => [need.equipmentId, need.units]));
    const chosen = new Set((section?.instructors ?? []).map((person) => person.id));

    openForm({
      title: section ? `Edit ${section.courseCode} section ${section.sectionNumber}` : `Add section to ${term.name}`,
      note: term.status === 'PUBLISHED' ? 'This term is published, so changing the room, times or instructors sends a notice to those affected.' : null,
      submitLabel: section ? 'Save changes' : 'Add section',
      body: [
        !section && field('Subject', subjectSelect),
        !section && field('Section number', numberInput),
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
        if (section) {
          await api.saveSection(term.id, section.id, { ...body, subjectId: section.subjectId, sectionNumber: section.sectionNumber });
        } else {
          await api.addSection(term.id, { ...body, subjectId: form.get('subjectId'), sectionNumber: form.get('sectionNumber') });
        }
        await selectTerm(term.id);
      },
    });
  }

  start();
  return el('div', { class: 'content' }, messages, panel);
}
