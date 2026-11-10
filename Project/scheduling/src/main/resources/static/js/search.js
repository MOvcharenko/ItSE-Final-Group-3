import { whenLabel } from './dates.js';
import { banner, dataTable, el } from './dom.js';
import { field, input, select } from './forms.js';
import { latestOnly, Superseded } from './http.js';
import { plural, ROOM_TYPES, roomTypeLabel } from './labels.js';
import { api } from './scheduling-api.js';

const KINDS = {
  ROOM: {
    note: 'Classes and approved bookings make a room busy. Pending requests don’t.',
    noun: ['room', 'rooms'],
  },
  EQUIPMENT: {
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
 * Finds rooms or equipment free for a time window, to request one. Returns the panel, plus setCatalog to offer the
 * equipment names and item types that exist (the API matches a whole name, so free text would easily find nothing)
 * and refresh to repeat the last search after a decision has changed what is free. onRequest gets the kind, the
 * resource and the window that was searched.
 */
export function availabilitySearchPanel(onRequest) {
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
  const findFree = latestOnly((searched, filters) => api.searchFree(searched, filters));
  let lastSearch = null;

  /** Results for the other kind no longer answer what is being asked, so they go. */
  function showKind() {
    const rooms = kind.value === 'ROOM';
    roomFields.forEach((item) => { item.hidden = !rooms; });
    itemFields.forEach((item) => { item.hidden = rooms; });
    note.textContent = KINDS[kind.value].note;
    findFree.drop();
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
    const { kind: searched, ...filters } = criteria;
    try {
      const found = await findFree(searched, filters);
      lastSearch = criteria;
      results.replaceChildren(showResults(found, criteria));
    } catch (error) {
      if (error instanceof Superseded) return; // a newer search has taken over
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
      el('p', { class: 'result-count' }, `${plural(found.length, one, many)} free, ${when}`),
      criteria.kind === 'ROOM' ? roomTable(found, criteria) : equipmentTable(found, criteria));
  }

  function requestColumn(criteria) {
    return {
      label: '',
      class: 'row-actions',
      cell: (resource) => el('button', {
        class: 'btn small',
        type: 'button',
        'aria-label': `Request ${resource.name}`,
        onclick: () => onRequest(criteria.kind, resource, criteria),
      }, 'Request'),
    };
  }

  function roomTable(rooms, criteria) {
    return dataTable([
      { label: 'Room', cell: (room) => room.name },
      { label: 'Building', cell: (room) => room.building },
      { label: 'Capacity', cell: (room) => String(room.capacity) },
      { label: 'Type', cell: (room) => roomTypeLabel(room.roomType) },
      { label: 'Equipment', cell: (room) => (room.fixedEquipment?.length ? room.fixedEquipment.join(', ') : '—') },
      requestColumn(criteria),
    ], rooms, { cards: true });
  }

  function equipmentTable(items, criteria) {
    return dataTable([
      { label: 'Item', cell: (item) => item.name },
      { label: 'Type', cell: (item) => item.itemType },
      { label: 'Free units', cell: (item) => `${item.freeUnits} of ${item.unitCount}` },
      { label: 'Home location', cell: (item) => item.homeLocation ?? '—' },
      requestColumn(criteria),
    ], items, { cards: true });
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
