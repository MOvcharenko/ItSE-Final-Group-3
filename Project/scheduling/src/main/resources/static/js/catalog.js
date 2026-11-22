import { banner, dataTable, el, filterRows } from './dom.js';
import { field, input, openForm, select } from './forms.js';
import { importPanel } from './import.js';
import { plural, ROOM_TYPES, roomTypeLabel } from './labels.js';
import { api } from './scheduling-api.js';

const STATUSES = [['AVAILABLE', 'Available'], ['MAINTENANCE', 'Maintenance'], ['CHECKED_OUT', 'Checked out']];

const list = (items) => (items?.length ? items.join(', ') : '—');
const splitList = (text) => text.split(',').map((item) => item.trim()).filter(Boolean);

const TABS = [['subjects', 'Subjects'], ['rooms', 'Rooms'], ['equipment', 'Equipment'], ['import', 'Import']];
const TAB_KEY = 'catalog.tab';

function savedTab() {
  try {
    const saved = localStorage.getItem(TAB_KEY);
    return TABS.some(([key]) => key === saved) ? saved : 'subjects';
  } catch {
    return 'subjects'; // storage can be blocked, e.g. in a private window
  }
}

/**
 * Subjects, rooms and equipment for Schedulers: see them, add to them, and change a room's or item's status. One list
 * at a time, in tabs: a faculty has hundreds of subjects, which would otherwise push the rooms out of reach.
 */
export function catalogScreen(user, setTopbar) {
  setTopbar([], []);
  const messages = el('div', { class: 'messages' });
  const counts = { subjects: null, rooms: null, equipment: null };
  let current = savedTab();
  let data = null;
  const tabButtons = new Map(TABS.map(([key, label]) => [key, el('button', {
    type: 'button',
    role: 'tab',
    id: `catalog-tab-${key}`,
    'aria-controls': 'catalog-tabpanel',
    onclick: () => choose(key),
    onkeydown: (event) => {
      const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
      if (!step) return;
      const keys = TABS.map(([tab]) => tab);
      const next = keys[(keys.indexOf(key) + step + keys.length) % keys.length];
      choose(next);
      tabButtons.get(next).focus();
    },
  }, label)]));
  const tabPanel = el('div', { id: 'catalog-tabpanel', role: 'tabpanel' }, el('div', { class: 'loading' }, 'Loading catalog…'));
  const importer = importPanel(() => load());

  function choose(key) {
    current = key;
    try {
      localStorage.setItem(TAB_KEY, key);
    } catch {
      // the choice then lasts until the page reloads
    }
    show();
  }

  function show() {
    tabButtons.forEach((button, key) => {
      const label = TABS.find(([tab]) => tab === key)[1];
      button.replaceChildren(label, counts[key] != null ? el('span', { class: 'count' }, String(counts[key])) : '');
      button.setAttribute('aria-selected', String(key === current));
      button.tabIndex = key === current ? 0 : -1;
    });
    tabPanel.setAttribute('aria-labelledby', `catalog-tab-${current}`);
    if (current === 'import') {
      tabPanel.replaceChildren(importer);
      return;
    }
    if (!data) return; // still loading
    tabPanel.replaceChildren({
      subjects: () => subjectsPanel(data.subjects),
      rooms: () => roomsPanel(data.rooms),
      equipment: () => equipmentPanel(data.equipment),
    }[current]());
  }

  async function load() {
    try {
      const [subjects, rooms, equipment] = await Promise.all([api.subjects(), api.rooms(), api.equipment()]);
      data = { subjects, rooms, equipment };
      Object.assign(counts, { subjects: subjects.length, rooms: rooms.length, equipment: equipment.length });
      messages.replaceChildren();
      show();
    } catch (error) {
      if (current !== 'import') tabPanel.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', load));
    }
  }

  /** A catalog list with its count, a search box and an Add button; columns as for dataTable. */
  function panel({ title, noun, addLabel, onAdd, columns, rows, emptyText, searchText, findLabel }) {
    const meta = el('span', { class: 'meta' }, plural(rows.length, noun));
    const table = rows.length ? dataTable(columns, rows, { searchText }) : null;
    const none = el('div', { class: 'empty', hidden: true }, `No ${noun}s match.`);
    const find = table && rows.length > 5 && filterRows(table, {
      placeholder: findLabel,
      onCount: (shown, total) => {
        meta.textContent = shown === total ? plural(total, noun) : `${shown} of ${plural(total, noun)}`;
        none.hidden = shown > 0;
      },
    });
    return el('section', { class: 'panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, title),
        el('div', { class: 'crumb' }, find, meta,
          el('button', { class: 'btn small', type: 'button', onclick: onAdd }, addLabel))),
      table ?? el('div', { class: 'empty' }, emptyText), none);
  }

  /** Changing the status saves at once; a refusal puts the old status back. item keeps the saved one for other tabs. */
  function statusControl(item, saveStatus) {
    const control = select('status', STATUSES, item.status, {
      'aria-label': `Status of ${item.name}`,
      onchange: async () => {
        try {
          await saveStatus(control.value);
          item.status = control.value;
          messages.replaceChildren();
        } catch (error) {
          control.value = item.status;
          messages.replaceChildren(banner(error.message));
        }
      },
    });
    return control;
  }

  /** Runs one "add" request and redraws the lists behind the form. */
  async function add(request) {
    await request;
    await load();
  }

  function subjectsPanel(subjects) {
    return panel({
      title: 'Subjects',
      noun: 'subject',
      addLabel: 'Add subject',
      onAdd: addSubject,
      columns: [
        { label: 'Code', cell: (subject) => subject.courseCode },
        { label: 'Title', cell: (subject) => subject.title },
        { label: 'Credits', cell: (subject) => String(subject.creditHours) },
        { label: 'Default room type', cell: (subject) => roomTypeLabel(subject.defaultRoomType) },
        { label: 'Department', cell: (subject) => subject.department ?? '—' },
        { label: 'Equipment needed', cell: (subject) => list(subject.typicalEquipment) },
        { label: 'Status', cell: (subject) => el('span', { class: subject.scheduled ? 'tag ok' : 'tag draft' },
          subject.scheduled ? 'Scheduled' : 'Not yet scheduled') },
      ],
      rows: subjects,
      emptyText: 'No subjects yet. Add the courses you want to schedule, or import a subject list.',
      findLabel: 'Find by code, title or department',
      searchText: (subject) => [subject.courseCode, subject.title, subject.department ?? '',
        subject.scheduled ? 'scheduled' : 'not yet scheduled'].join(' '),
    });
  }

  function addSubject() {
    openForm({
      title: 'Add subject',
      submitLabel: 'Add subject',
      body: [
        field('Course code', input('courseCode', { required: true, maxlength: 30, placeholder: 'MATH 210' })),
        field('Title', input('title', { required: true, maxlength: 200, placeholder: 'Linear Algebra' })),
        field('Credit hours', input('creditHours', { type: 'number', required: true, min: 1, value: 3 })),
        field('Default room type', select('defaultRoomType', ROOM_TYPES, 'LECTURE_HALL')),
        field('Department (optional)', input('department', { maxlength: 100, placeholder: 'MATH' }), 'It must be a department your account manages.'),
        field('Equipment needed (optional)', input('typicalEquipment', { placeholder: 'Projector, Whiteboard' }), 'Separate items with commas.'),
      ],
      onSubmit: (form) => add(api.addSubject({
        courseCode: form.get('courseCode'),
        title: form.get('title'),
        creditHours: Number(form.get('creditHours')),
        defaultRoomType: form.get('defaultRoomType'),
        department: form.get('department') || null,
        typicalEquipment: splitList(form.get('typicalEquipment')),
      })),
    });
  }

  function roomsPanel(rooms) {
    return panel({
      title: 'Rooms',
      noun: 'room',
      addLabel: 'Add room',
      onAdd: addRoom,
      columns: [
        { label: 'Room', cell: (room) => room.name },
        { label: 'Building', cell: (room) => room.building },
        { label: 'Capacity', cell: (room) => String(room.capacity) },
        { label: 'Type', cell: (room) => roomTypeLabel(room.roomType) },
        { label: 'Equipment', cell: (room) => list(room.fixedEquipment) },
        { label: 'Status', cell: (room) => statusControl(room, (status) => api.setRoomStatus(room.id, status)) },
      ],
      rows: rooms,
      emptyText: 'No rooms yet. Add the rooms that classes can use, or import a room list.',
      findLabel: 'Find by room, building or equipment',
      searchText: (room) => [room.name, room.building, roomTypeLabel(room.roomType), ...(room.fixedEquipment ?? []),
        STATUSES.find(([value]) => value === room.status)?.[1] ?? ''].join(' '),
    });
  }

  function addRoom() {
    openForm({
      title: 'Add room',
      submitLabel: 'Add room',
      body: [
        field('Room name', input('name', { required: true, maxlength: 100, placeholder: 'Founders 110' })),
        field('Building', input('building', { required: true, maxlength: 100, placeholder: 'Founders Hall' })),
        field('Capacity', input('capacity', { type: 'number', required: true, min: 1 })),
        field('Room type', select('roomType', ROOM_TYPES, 'LECTURE_HALL')),
        field('Fixed equipment (optional)', input('fixedEquipment', { placeholder: 'Projector, Whiteboard' }), 'Separate items with commas.'),
      ],
      onSubmit: (form) => add(api.addRoom({
        name: form.get('name'),
        building: form.get('building'),
        capacity: Number(form.get('capacity')),
        roomType: form.get('roomType'),
        fixedEquipment: splitList(form.get('fixedEquipment')),
      })),
    });
  }

  function equipmentPanel(equipment) {
    return panel({
      title: 'Equipment',
      noun: 'item',
      addLabel: 'Add equipment',
      onAdd: addEquipment,
      columns: [
        { label: 'Item', cell: (item) => item.name },
        { label: 'Type', cell: (item) => item.itemType },
        { label: 'Units', cell: (item) => String(item.unitCount) },
        { label: 'Home location', cell: (item) => item.homeLocation ?? '—' },
        { label: 'Status', cell: (item) => statusControl(item, (status) => api.setEquipmentStatus(item.id, status)) },
      ],
      rows: equipment,
      emptyText: 'No equipment yet. Add portable items that sections can reserve.',
      findLabel: 'Find by item, type or location',
      searchText: (item) => [item.name, item.itemType, item.homeLocation ?? '',
        STATUSES.find(([value]) => value === item.status)?.[1] ?? ''].join(' '),
    });
  }

  function addEquipment() {
    openForm({
      title: 'Add equipment',
      submitLabel: 'Add equipment',
      body: [
        field('Item name', input('name', { required: true, maxlength: 100, placeholder: 'Portable mic kit' })),
        field('Item type', input('itemType', { required: true, maxlength: 100, placeholder: 'Audio' })),
        field('Units', input('unitCount', { type: 'number', required: true, min: 1, value: 1 })),
        field('Home location (optional)', input('homeLocation', { maxlength: 200, placeholder: 'AV Storage, Hale Hall' })),
      ],
      onSubmit: (form) => add(api.addEquipment({
        name: form.get('name'),
        itemType: form.get('itemType'),
        unitCount: Number(form.get('unitCount')),
        homeLocation: form.get('homeLocation') || null,
      })),
    });
  }

  show();
  load();
  return el('div', { class: 'content' }, messages,
    el('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Catalog' }, [...tabButtons.values()]),
    tabPanel);
}
