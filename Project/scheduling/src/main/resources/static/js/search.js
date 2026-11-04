import { getJson } from './api.js';
import { ROOM_TYPES, roomTypeLabel } from './catalog.js';
import { whenLabel } from './dates.js';
import { banner, el } from './dom.js';
import { field, input, select } from './forms.js';

const KINDS = {
  ROOM: {
    path: '/api/search/rooms',
    note: 'Classes and approved bookings make a room busy. Pending requests don’t.',
    noun: ['room', 'rooms'],
  },
  EQUIPMENT: {
    path: '/api/search/equipment',
    note: 'Classes and approved bookings use up units. Pending requests don’t.',
    noun: ['item', 'items'],
  },
};

/** Replaces a filter's options with the names given, one per name whatever its case, and keeps the choice if it can. */
function fillNames(control, anyLabel, names) {
  const unique = new Map();
  names.forEach((name) => {
    if (!unique.has(name.toLowerCase())) unique.set(name.toLowerCase(), name);
  });
  const chosen = control.value;
  control.replaceChildren(el('option', { value: '' }, anyLabel),
    ...[...unique.values()].sort((a, b) => a.localeCompare(b)).map((name) => el('option', { value: name }, name)));
  if ([...control.options].some((option) => option.value === chosen)) control.value = chosen;
}

/**
 * Finds rooms or equipment free for a time window. Returns the panel, plus setCatalog to offer the equipment names and
 * item types that exist (the API matches a whole name, so free text would easily find nothing) and refresh to repeat
 * the last search after a decision has changed what is free. onRequest gets the kind, the resource and the window
 * that was searched.
 */
export function availabilityPanel(onRequest) {
  const kind = select('kind', [['ROOM', 'Room'], ['EQUIPMENT', 'Equipment']], 'ROOM', { onchange: showKind });
  const start = input('start', { type: 'datetime-local', required: true });
  const end = input('end', { type: 'datetime-local', required: true });
  const minCapacity = input('minCapacity', { type: 'number', min: 1, placeholder: 'Any' });
  const roomType = select('roomType', [['', 'Any type'], ...ROOM_TYPES], '');
  const equipment = select('equipment', [['', 'Any equipment']], '');
  const itemType = select('itemType', [['', 'Any type']], '');
  const roomFields = [field('Seats at least', minCapacity), field('Room type', roomType), field('Equipment', equipment)];
  const itemFields = [field('Item type', itemType)];
  const note = el('span', { class: 'meta' });
  const results = el('div', { 'aria-live': 'polite' });
  let lastSearch = null;
  let requestNumber = 0;

  /** Results for the other kind no longer answer what is being asked, so they go. */
  function showKind() {
    const rooms = kind.value === 'ROOM';
    roomFields.forEach((item) => { item.hidden = !rooms; });
    itemFields.forEach((item) => { item.hidden = rooms; });
    note.textContent = KINDS[kind.value].note;
    requestNumber++;
    lastSearch = null;
    results.replaceChildren();
  }

  function setCatalog(allRooms, allEquipment) {
    fillNames(equipment, 'Any equipment', allRooms.flatMap((room) => room.fixedEquipment ?? []));
    fillNames(itemType, 'Any type', allEquipment.map((item) => item.itemType));
  }

  function criteriaFromForm() {
    const window = { kind: kind.value, start: start.value, end: end.value };
    return kind.value === 'ROOM'
      ? { ...window, minCapacity: minCapacity.value, roomType: roomType.value, equipment: equipment.value }
      : { ...window, itemType: itemType.value };
  }

  async function search(criteria) {
    const thisRequest = ++requestNumber;
    const { kind: searched, ...filters } = criteria;
    const params = new URLSearchParams(Object.entries(filters).filter(([, value]) => value));
    try {
      const found = await getJson(`${KINDS[searched].path}?${params}`);
      if (thisRequest !== requestNumber) return; // a newer search has taken over
      lastSearch = criteria;
      results.replaceChildren(showResults(found, criteria));
    } catch (error) {
      if (thisRequest !== requestNumber) return;
      lastSearch = null;
      results.replaceChildren(el('div', { class: 'panel-body' }, banner(error.message)));
    }
  }

  function showResults(found, criteria) {
    const when = whenLabel(criteria);
    const [one, many] = KINDS[criteria.kind].noun;
    if (!found.length) {
      return el('div', { class: 'empty' },
        el('strong', {}, `No ${many} match for ${when}`),
        'Try another time, or fewer requirements.');
    }
    return el('div', {},
      el('p', { class: 'result-count' }, `${found.length === 1 ? `1 ${one}` : `${found.length} ${many}`} free, ${when}`),
      criteria.kind === 'ROOM' ? roomTable(found, criteria) : equipmentTable(found, criteria));
  }

  function table(columns, rows) {
    return el('table', { class: 'cards' },
      el('thead', {}, el('tr', {}, [...columns, ''].map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, rows));
  }

  function requestCell(resource, criteria) {
    return el('td', { class: 'row-actions' },
      el('button', {
        class: 'btn small',
        type: 'button',
        'aria-label': `Request ${resource.name}`,
        onclick: () => onRequest(criteria.kind, resource, criteria),
      }, 'Request'));
  }

  function roomTable(rooms, criteria) {
    return table(['Room', 'Building', 'Capacity', 'Type', 'Equipment'], rooms.map((room) => el('tr', {},
      el('td', { 'data-label': 'Room' }, room.name),
      el('td', { 'data-label': 'Building' }, room.building),
      el('td', { 'data-label': 'Capacity' }, String(room.capacity)),
      el('td', { 'data-label': 'Type' }, roomTypeLabel(room.roomType)),
      el('td', { 'data-label': 'Equipment' }, room.fixedEquipment?.length ? room.fixedEquipment.join(', ') : '—'),
      requestCell(room, criteria))));
  }

  function equipmentTable(items, criteria) {
    return table(['Item', 'Type', 'Free units', 'Home location'], items.map((item) => el('tr', {},
      el('td', { 'data-label': 'Item' }, item.name),
      el('td', { 'data-label': 'Type' }, item.itemType),
      el('td', { 'data-label': 'Free units' }, `${item.freeUnits} of ${item.unitCount}`),
      el('td', { 'data-label': 'Home location' }, item.homeLocation ?? '—'),
      requestCell(item, criteria))));
  }

  const element = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'Search availability'), note),
    el('form', {
      class: 'toolbar',
      onsubmit: (event) => {
        event.preventDefault();
        search(criteriaFromForm());
      },
    },
      field('Looking for', kind),
      field('From', start),
      field('To', end),
      roomFields,
      itemFields,
      el('button', { class: 'btn primary', type: 'submit' }, 'Search')),
    results);

  showKind();
  return { element, setCatalog, refresh: () => { if (lastSearch) search(lastSearch); } };
}
