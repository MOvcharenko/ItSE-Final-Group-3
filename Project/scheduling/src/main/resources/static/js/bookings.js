import { getJson, post } from './api.js';
import { whenLabel } from './dates.js';
import { banner, el } from './dom.js';
import { field, input, openForm, select } from './forms.js';
import { availabilityPanel } from './search.js';

const RESOURCE_TYPES = [['ROOM', 'Room'], ['EQUIPMENT', 'Equipment']];
const STATUS_TAGS = {
  PENDING: ['tag draft', 'Pending'],
  APPROVED: ['tag ok', 'Approved'],
  CANCELLED: ['tag cancelled', 'Cancelled'],
};

/** Requesting a room or equipment, and the list of requests. A Scheduler sees all of them, an Instructor their own. */
export function bookingsScreen(user, setTopbar) {
  const scheduler = user.role === 'SCHEDULER';
  const state = { bookings: [], rooms: [], equipment: [] };
  const messages = el('div', { class: 'messages' });
  const meta = el('span', { class: 'meta' });
  const list = el('div', {}, el('div', { class: 'loading' }, 'Loading requests…'));

  const requestButton = el('button', { class: 'btn primary', type: 'button', disabled: true, onclick: () => requestForm() }, 'Request booking');
  setTopbar([], [requestButton]);
  const search = availabilityPanel((type, resource, { start, end }) => requestForm({ type, resourceId: resource.id, start, end }));
  const statusFilter = select('status',
    [['', 'All statuses'], ...Object.entries(STATUS_TAGS).map(([value, [, label]]) => [value, label])], '',
    { 'aria-label': 'Filter by status', onchange: load });
  let requestNumber = 0;

  async function load() {
    const thisRequest = ++requestNumber;
    const status = statusFilter.value;
    const bookingsPath = status ? `/api/bookings?status=${status}` : '/api/bookings';
    try {
      const [bookings, rooms, equipment] = await Promise.all([bookingsPath, '/api/rooms', '/api/equipment'].map(getJson));
      if (thisRequest !== requestNumber) return; // a newer filter choice has taken over
      Object.assign(state, { bookings, rooms, equipment });
      search.setCatalog(rooms, equipment);
      messages.replaceChildren();
      requestButton.disabled = false;
      render();
    } catch (error) {
      if (thisRequest !== requestNumber) return;
      list.replaceChildren();
      meta.textContent = '';
      messages.replaceChildren(banner(error.message, 'Try again', load));
    }
  }

  function render() {
    const { bookings } = state;
    meta.textContent = bookings.length === 1 ? '1 request' : `${bookings.length} requests`;
    if (!bookings.length && statusFilter.value) {
      list.replaceChildren(el('div', { class: 'empty' },
        el('strong', {}, `No ${statusFilter.selectedOptions[0].textContent.toLowerCase()} requests`),
        el('button', {
          class: 'btn',
          type: 'button',
          onclick: () => { statusFilter.value = ''; load(); },
        }, 'Show all requests')));
      return;
    }
    if (!bookings.length) {
      list.replaceChildren(el('div', { class: 'empty' },
        el('strong', {}, 'No booking requests yet'),
        'Request a room or equipment for a review session, makeup exam or event.'));
      return;
    }
    const columns = [scheduler && 'Requested by', 'Purpose', 'Type', 'Room or equipment', 'When', 'Status', scheduler && ''].filter((name) => name !== false);
    list.replaceChildren(el('table', { class: 'cards' },
      el('thead', {}, el('tr', {}, columns.map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, bookings.map(bookingRow))));
  }

  function bookingRow(booking) {
    const [tagClass, statusLabel] = STATUS_TAGS[booking.status];
    return el('tr', {},
      scheduler && el('td', { 'data-label': 'Requested by' }, booking.requesterName),
      el('td', { 'data-label': 'Purpose' }, booking.purpose),
      el('td', { 'data-label': 'Type' }, RESOURCE_TYPES.find(([value]) => value === booking.resourceType)[1]),
      el('td', { 'data-label': 'Room or equipment' }, booking.resourceName),
      el('td', { class: 'nowrap', 'data-label': 'When' }, whenLabel(booking)),
      el('td', { 'data-label': 'Status' }, el('span', { class: tagClass }, statusLabel)),
      scheduler && el('td', { class: 'row-actions' }, decisionButtons(booking)));
  }

  /** Pending can be approved or declined, Approved can only be cancelled, and Cancelled is final. */
  function decisionButtons(booking) {
    const name = `${booking.purpose}, ${booking.resourceName}`;
    if (booking.status === 'CANCELLED') return [];
    const decline = booking.status === 'PENDING';
    return [
      decline && el('button', {
        class: 'btn small',
        type: 'button',
        'aria-label': `Approve ${name}`,
        onclick: (event) => approve(booking, event.target),
      }, 'Approve'),
      el('button', {
        class: 'btn quiet small',
        type: 'button',
        'aria-label': `${decline ? 'Decline' : 'Cancel'} ${name}`,
        onclick: () => cancelForm(booking),
      }, decline ? 'Decline' : 'Cancel booking'),
    ];
  }

  /**
   * A refusal (409) most often means the room is already taken. The list is reloaded either way, because a refusal can
   * also mean that someone else has just decided this request.
   */
  async function approve(booking, button) {
    button.disabled = true;
    try {
      await post(`/api/bookings/${encodeURIComponent(booking.id)}/approve`);
      await load();
      search.refresh();
    } catch (error) {
      await load();
      messages.replaceChildren(banner(error.message));
    }
  }

  function cancelForm(booking) {
    const decline = booking.status === 'PENDING';
    const note = decline
      ? `${booking.requesterName} will get a notice that this request was declined.`
      : `${booking.resourceName} becomes free again, and ${booking.requesterName} will get a notice.`;
    openForm({
      title: decline ? `Decline “${booking.purpose}”` : `Cancel “${booking.purpose}”`,
      note,
      submitLabel: decline ? 'Decline request' : 'Cancel booking',
      closeLabel: 'Go back',
      body: [field('Note to the requester (optional)', el('textarea', { name: 'note', rows: 3, maxlength: 500 }))],
      onSubmit: async (form) => {
        await post(`/api/bookings/${encodeURIComponent(booking.id)}/cancel`, { note: form.get('note') || null });
        await load();
        search.refresh();
      },
    });
  }

  /** Maintenance and Checked out items can't be booked, so they aren't offered. */
  function resourceOptions(type) {
    const rooms = type === 'ROOM';
    const available = (rooms ? state.rooms : state.equipment).filter((item) => item.status === 'AVAILABLE');
    if (!available.length) return [['', rooms ? 'No rooms available' : 'No equipment available']];
    return [['', rooms ? 'Choose a room' : 'Choose an item'], ...available.map((item) => [item.id,
      rooms ? `${item.name} (${item.capacity} seats)` : `${item.name} (${item.unitCount} units)`])];
  }

  /** prefill comes from a search result: { type, resourceId, start, end }. */
  function requestForm(prefill = {}) {
    const chosenType = prefill.type ?? 'ROOM';
    const resource = select('resourceId', resourceOptions(chosenType), prefill.resourceId ?? '', { required: true });
    const type = select('resourceType', RESOURCE_TYPES, chosenType, {
      onchange: () => {
        resource.replaceChildren(...resourceOptions(type.value).map(([value, label]) => el('option', { value }, label)));
      },
    });
    openForm({
      title: 'Request booking',
      note: 'A request reserves nothing until a Scheduler approves it.',
      submitLabel: 'Send request',
      body: [
        field('Type', type),
        field('Room or equipment', resource),
        field('Start', input('start', { type: 'datetime-local', required: true, value: prefill.start })),
        field('End', input('end', { type: 'datetime-local', required: true, value: prefill.end })),
        field('Purpose', input('purpose', { required: true, maxlength: 500, placeholder: 'Exam review session' })),
      ],
      onSubmit: async (form) => {
        await post('/api/bookings', {
          resourceType: form.get('resourceType'),
          resourceId: form.get('resourceId'),
          start: form.get('start'),
          end: form.get('end'),
          purpose: form.get('purpose'),
        });
        statusFilter.value = ''; // so a filter can't hide the request just sent
        await load();
      },
    });
  }

  load();
  return el('div', { class: 'content' }, messages, search.element,
    el('section', { class: 'panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, scheduler ? 'Booking requests' : 'My requests'),
        el('div', { class: 'crumb' }, meta, statusFilter)),
      list));
}
