import { banner, dataTable, el } from './dom.js';
import { field, input, openForm, select } from './forms.js';
import { importPanel } from './import.js';
import { plural, ROOM_TYPES, roomTypeLabel } from './labels.js';
import { api } from './scheduling-api.js';

const STATUSES = [['AVAILABLE', 'Available'], ['MAINTENANCE', 'Maintenance'], ['CHECKED_OUT', 'Checked out']];

const list = (items) => (items?.length ? items.join(', ') : '—');
const splitList = (text) => text.split(',').map((item) => item.trim()).filter(Boolean);

/** Subjects, rooms and equipment for Schedulers: see them, add to them, and change a room's or item's status. */
export function catalogScreen(user, setTopbar) {
  setTopbar([], []);
  const messages = el('div', { class: 'messages' });
  const panels = el('div', { class: 'stack' }, el('div', { class: 'loading' }, 'Loading catalog…'));

  async function load() {
    try {
      const [subjects, rooms, equipment] = await Promise.all([api.subjects(), api.rooms(), api.equipment()]);
      messages.replaceChildren();
      panels.replaceChildren(subjectsPanel(subjects), roomsPanel(rooms), equipmentPanel(equipment));
    } catch (error) {
      panels.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', load));
    }
  }

  /** A catalog list with its count and Add button; columns as for dataTable. */
  function panel({ title, noun, addLabel, onAdd, columns, rows, emptyText }) {
    return el('section', { class: 'panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, title),
        el('div', { class: 'crumb' },
          el('span', { class: 'meta' }, plural(rows.length, noun)),
          el('button', { class: 'btn small', type: 'button', onclick: onAdd }, addLabel))),
      rows.length ? dataTable(columns, rows) : el('div', { class: 'empty' }, emptyText));
  }

  /** Changing the status saves at once; a refusal puts the old status back. */
  function statusControl(name, saveStatus, status) {
    const control = select('status', STATUSES, status, {
      'aria-label': `Status of ${name}`,
      onchange: async () => {
        try {
          await saveStatus(control.value);
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
      emptyText: 'No subjects yet. Add the courses you want to schedule.',
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
        { label: 'Status', cell: (room) => statusControl(room.name, (status) => api.setRoomStatus(room.id, status), room.status) },
      ],
      rows: rooms,
      emptyText: 'No rooms yet. Add the rooms that classes can use.',
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
        { label: 'Status', cell: (item) => statusControl(item.name, (status) => api.setEquipmentStatus(item.id, status), item.status) },
      ],
      rows: equipment,
      emptyText: 'No equipment yet. Add portable items that sections can reserve.',
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

  load();
  return el('div', { class: 'content' }, messages, importPanel(load), panels);
}
