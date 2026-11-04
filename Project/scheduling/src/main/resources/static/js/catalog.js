import { getJson, patch, post } from './api.js';
import { banner, el } from './dom.js';
import { field, input, openForm, select } from './forms.js';
import { importPanel } from './import.js';

export const ROOM_TYPES = [['LECTURE_HALL', 'Lecture hall'], ['LAB', 'Lab'], ['SEMINAR_ROOM', 'Seminar room']];
const STATUSES = [['AVAILABLE', 'Available'], ['MAINTENANCE', 'Maintenance'], ['CHECKED_OUT', 'Checked out']];

export const roomTypeLabel = (type) => ROOM_TYPES.find(([value]) => value === type)?.[1] ?? type;
const list = (items) => (items?.length ? items.join(', ') : '—');
const splitList = (text) => text.split(',').map((item) => item.trim()).filter(Boolean);

/** Subjects, rooms and equipment for Schedulers: see them, add to them, and change a room's or item's status. */
export function catalogScreen(user, setTopbar) {
  setTopbar([], []);
  const messages = el('div', { class: 'messages' });
  const panels = el('div', { class: 'stack' }, el('div', { class: 'loading' }, 'Loading catalog…'));

  async function load() {
    try {
      const [subjects, rooms, equipment] = await Promise.all(
        ['/api/subjects', '/api/rooms', '/api/equipment'].map(getJson));
      messages.replaceChildren();
      panels.replaceChildren(subjectsPanel(subjects), roomsPanel(rooms), equipmentPanel(equipment));
    } catch (error) {
      panels.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', load));
    }
  }

  function panel(title, count, addLabel, onAdd, columns, rows, emptyText) {
    return el('section', { class: 'panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, title),
        el('div', { class: 'crumb' },
          el('span', { class: 'meta' }, count),
          el('button', { class: 'btn small', type: 'button', onclick: onAdd }, addLabel))),
      rows.length
        ? el('div', { class: 'scroll-x' }, el('table', {},
          el('thead', {}, el('tr', {}, columns.map((name) => el('th', { scope: 'col' }, name)))),
          el('tbody', {}, rows)))
        : el('div', { class: 'empty' }, emptyText));
  }

  function statusControl(name, path, status) {
    const control = select('status', STATUSES, status, {
      'aria-label': `Status of ${name}`,
      onchange: async () => {
        try {
          await patch(path, { status: control.value });
          messages.replaceChildren();
        } catch (error) {
          control.value = status;
          messages.replaceChildren(banner(error.message));
        }
      },
    });
    return control;
  }

  /** Runs one "add" request and redraws the lists behind the form. */
  async function add(path, body) {
    await post(path, body);
    await load();
  }

  function subjectsPanel(subjects) {
    const rows = subjects.map((subject) => el('tr', {},
      el('td', {}, subject.courseCode),
      el('td', {}, subject.title),
      el('td', {}, String(subject.creditHours)),
      el('td', {}, roomTypeLabel(subject.defaultRoomType)),
      el('td', {}, subject.department ?? '—'),
      el('td', {}, list(subject.typicalEquipment)),
      el('td', {}, el('span', { class: subject.scheduled ? 'tag ok' : 'tag draft' }, subject.scheduled ? 'Scheduled' : 'Not yet scheduled'))));
    return panel('Subjects', count(subjects, 'subject'), 'Add subject', addSubject,
      ['Code', 'Title', 'Credits', 'Default room type', 'Department', 'Equipment needed', 'Status'], rows,
      'No subjects yet. Add the courses you want to schedule.');
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
      onSubmit: (form) => add('/api/subjects', {
        courseCode: form.get('courseCode'),
        title: form.get('title'),
        creditHours: Number(form.get('creditHours')),
        defaultRoomType: form.get('defaultRoomType'),
        department: form.get('department') || null,
        typicalEquipment: splitList(form.get('typicalEquipment')),
      }),
    });
  }

  function roomsPanel(rooms) {
    const rows = rooms.map((room) => el('tr', {},
      el('td', {}, room.name),
      el('td', {}, room.building),
      el('td', {}, String(room.capacity)),
      el('td', {}, roomTypeLabel(room.roomType)),
      el('td', {}, list(room.fixedEquipment)),
      el('td', {}, statusControl(room.name, `/api/rooms/${encodeURIComponent(room.id)}/status`, room.status))));
    return panel('Rooms', count(rooms, 'room'), 'Add room', addRoom,
      ['Room', 'Building', 'Capacity', 'Type', 'Equipment', 'Status'], rows,
      'No rooms yet. Add the rooms that classes can use.');
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
      onSubmit: (form) => add('/api/rooms', {
        name: form.get('name'),
        building: form.get('building'),
        capacity: Number(form.get('capacity')),
        roomType: form.get('roomType'),
        fixedEquipment: splitList(form.get('fixedEquipment')),
      }),
    });
  }

  function equipmentPanel(equipment) {
    const rows = equipment.map((item) => el('tr', {},
      el('td', {}, item.name),
      el('td', {}, item.itemType),
      el('td', {}, String(item.unitCount)),
      el('td', {}, item.homeLocation ?? '—'),
      el('td', {}, statusControl(item.name, `/api/equipment/${encodeURIComponent(item.id)}/status`, item.status))));
    return panel('Equipment', count(equipment, 'item'), 'Add equipment', addEquipment,
      ['Item', 'Type', 'Units', 'Home location', 'Status'], rows,
      'No equipment yet. Add portable items that sections can reserve.');
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
      onSubmit: (form) => add('/api/equipment', {
        name: form.get('name'),
        itemType: form.get('itemType'),
        unitCount: Number(form.get('unitCount')),
        homeLocation: form.get('homeLocation') || null,
      }),
    });
  }

  load();
  return el('div', { class: 'content' }, messages, importPanel(load), panels);
}

function count(items, noun) {
  return items.length === 1 ? `1 ${noun}` : `${items.length} ${noun}s`;
}
