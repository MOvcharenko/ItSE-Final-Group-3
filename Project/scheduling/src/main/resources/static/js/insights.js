import { banner, dataTable, el } from './dom.js';
import { latestOnly, Superseded } from './http.js';
import { plural } from './labels.js';
import { api } from './scheduling-api.js';
import { initialTerm, termPicker } from './terms.js';

const INSTRUCTOR_STATUS = {
  UNDERUSED: ['tag insight-alert', 'Under-used (<50%)', 'var(--color-amber, #d97706)'],
  CONFIRMATION: ['tag insight-success', 'Full load (≥80%)', 'var(--color-green, #16a34a)'],
  HEALTHY: ['tag', 'Healthy', 'var(--color-blue, #2563eb)'],
};
const ROOM_STATUS = {
  UNDERUSED: ['tag insight-alert', 'Room underused'],
  OVERSIZED: ['tag insight-amber', 'Oversized'],
  HEALTHY: ['tag insight-success', 'Well utilized'],
};

const statusTag = ([tagClass, text]) => el('span', { class: tagClass }, text);

/** An advisory suggestion, with a link to Conflicts when one is already open for the same resource. */
function suggestionCard(suggestion, onDismiss) {
  const kind = suggestion.axis === 'INSTRUCTOR_UTILIZATION' ? 'Instructor load'
    : suggestion.measure === 'OCCUPANCY' ? 'Room occupancy' : 'Room fill';
  return el('div', { class: 'insight-card' },
    el('div', { class: 'insight-card-header' },
      el('div', { class: 'insight-card-titles' },
        el('strong', {}, suggestion.resourceName || 'Resource'),
        el('span', { class: 'tag insight-tag' }, kind),
        suggestion.alreadyFlagged && el('a', {
          class: 'tag insight-conflict-tag',
          href: '#conflicts',
          title: 'An operational conflict is already open for this resource. Click to view.',
        }, 'Already flagged in Conflicts →')),
      el('button', { class: 'btn quiet small', type: 'button', onclick: () => onDismiss(suggestion.id) }, 'Dismiss suggestion')),
    el('p', { class: 'insight-card-message' }, suggestion.message));
}

function suggestionsPanel(suggestions, onDismiss) {
  return el('section', { class: 'panel insight-panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, 'Advisory suggestions'),
      el('span', { class: 'meta' }, plural(suggestions.length, 'suggestion'))),
    el('div', { class: 'panel-body' },
      suggestions.length === 0
        ? el('p', { class: 'hint' }, 'No active suggestions for this term. Schedule utilization is healthy!')
        : el('div', { class: 'insight-cards-grid' }, suggestions.map((suggestion) => suggestionCard(suggestion, onDismiss)))));
}

/** Scheduled against declared hours, capped at a full bar. */
function utilizationBar(instructor) {
  const percent = Math.min(Math.round(instructor.ratioPercent), 100);
  const [, , color] = INSTRUCTOR_STATUS[instructor.status] ?? INSTRUCTOR_STATUS.HEALTHY;
  return el('div', { class: 'progress-wrapper' },
    el('div', { class: 'progress-track' },
      el('div', { class: 'progress-fill', style: `width: ${percent}%; background: ${color};` })),
    el('span', { class: 'progress-label' }, `${Math.round(instructor.ratioPercent)}%`));
}

/** FR5.1 */
function instructorUtilizationPanel(instructors) {
  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, 'Instructor schedule utilization'),
      el('span', { class: 'meta' }, `${instructors.length} declared`)),
    dataTable([
      { label: 'Instructor', cell: (instructor) => el('strong', {}, instructor.instructorName) },
      { label: 'Scheduled / Declared', cell: (instructor) => `${instructor.scheduledHours} h of ${instructor.declaredHours} h` },
      { label: 'Utilization', cell: utilizationBar },
      { label: 'Status', cell: (instructor) => statusTag(INSTRUCTOR_STATUS[instructor.status] ?? INSTRUCTOR_STATUS.HEALTHY) },
    ], instructors, { cards: true }));
}

/** FR5.2 */
function roomUtilizationPanel(rooms) {
  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, 'Room occupancy & fill'),
      el('span', { class: 'meta' }, plural(rooms.length, 'room'))),
    dataTable([
      { label: 'Room', cell: (room) => [el('strong', {}, room.roomName), el('div', { class: 'muted' }, `Capacity: ${room.capacity}`)] },
      { label: 'Weekly scheduled', cell: (room) => `${room.scheduledHours} h / week` },
      { label: 'Occupancy (of 50h)', cell: (room) => `${Math.round(room.occupancyPercent)}%` },
      { label: 'Avg fill', cell: (room) => `${Math.round(room.fillPercent)}%` },
      { label: 'Status', cell: (room) => statusTag(ROOM_STATUS[room.status] ?? ROOM_STATUS.HEALTHY) },
    ], rooms, { cards: true }));
}

/**
 * Advisory Optimization Insights Screen (FR5, UC-13, TR2.9, NFR4.4).
 * Visually distinct from operational conflicts: shows instructor workload bars, room occupancy & fill,
 * suggestion cards, positive confirmations, and deduplication badges.
 */
export function insightsScreen(user, setTopbar) {
  let term = null;
  const messages = el('div', { class: 'messages' });
  const body = el('div', { class: 'insights-content', 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading optimization insights…'));
  const picker = termPicker((chosen) => selectTerm(chosen));
  const links = [el('a', { class: 'btn', href: '#timetable' }, 'Open Timetable'), el('a', { class: 'btn', href: '#conflicts' }, 'View Conflicts')];
  const fetchInsights = latestOnly((termId) => api.insights(termId));

  /** The one current term: loading and dismissing both read it, so they can't disagree. */
  function selectTerm(chosen) {
    term = chosen;
    picker.show(term);
    setTopbar([picker.select, picker.tag], links);
    load();
  }

  async function load() {
    body.setAttribute('aria-busy', 'true');
    try {
      const { instructors, rooms, suggestions } = await fetchInsights(term.id);
      messages.replaceChildren();
      body.replaceChildren(
        suggestionsPanel(suggestions.filter((suggestion) => !suggestion.dismissed), dismissSuggestion),
        el('div', { class: 'two-col' }, instructorUtilizationPanel(instructors), roomUtilizationPanel(rooms)));
    } catch (error) {
      if (error instanceof Superseded) return;
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', load));
    }
    body.removeAttribute('aria-busy');
  }

  async function dismissSuggestion(id) {
    try {
      await api.dismissInsight(id);
      load();
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    }
  }

  async function start() {
    let terms;
    try {
      terms = await api.terms();
    } catch (error) {
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => { messages.replaceChildren(); start(); }));
      return;
    }
    if (!terms.length) {
      body.replaceChildren(el('section', { class: 'panel' }, el('div', { class: 'empty' },
        el('strong', {}, 'No terms yet'),
        'Create a term in Timetable to see optimization insights.')));
      return;
    }
    picker.setTerms(terms);
    selectTerm(initialTerm(terms));
  }

  start();
  return el('div', { class: 'content' }, messages, body);
}
