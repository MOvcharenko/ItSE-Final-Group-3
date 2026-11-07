import { chosenTermId, getJson, post, rememberTerm } from './api.js';
import { banner, el } from './dom.js';

const termLabel = (term) => (term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name);

/**
 * Advisory Optimization Insights Screen (FR5, UC-13, TR2.9, NFR4.4).
 * Visually distinct from operational conflicts: shows instructor workload bars, room occupancy & fill,
 * suggestion cards, positive confirmations, and deduplication badges.
 */
export function insightsScreen(user, setTopbar) {
  const state = { terms: [], term: null, insights: null };
  const messages = el('div', { class: 'messages' });
  const body = el('div', { class: 'insights-content', 'aria-live': 'polite' }, el('div', { class: 'loading' }, 'Loading optimization insights…'));
  const termSelect = el('select', { 'aria-label': 'Term', onchange: () => selectTerm(termSelect.value) });
  const termTag = el('span', { class: 'tag' });
  let requestNumber = 0;

  function showTopbar(term) {
    const draft = term.status === 'DRAFT';
    termTag.className = draft ? 'tag draft' : 'tag published';
    termTag.textContent = draft ? 'Draft' : 'Published';
    termSelect.value = term.id;
    setTopbar([termSelect, termTag], [
      el('a', { class: 'btn', href: '#timetable' }, 'Open Timetable'),
      el('a', { class: 'btn', href: '#conflicts' }, 'View Conflicts'),
    ]);
  }

  /** The one current term: loading and dismissing both read state.term, so they can't disagree. */
  function selectTerm(id) {
    const chosen = state.terms.find((t) => t.id === id);
    if (!chosen) return;
    state.term = chosen;
    rememberTerm(id);
    showTopbar(chosen);
    load(id);
  }

  async function load(termId) {
    const thisRequest = ++requestNumber;
    body.setAttribute('aria-busy', 'true');
    try {
      const insights = await getJson(`/api/terms/${encodeURIComponent(termId)}/insights`);
      if (thisRequest !== requestNumber) return;
      state.insights = insights;
      messages.replaceChildren();
      renderInsights();
    } catch (error) {
      if (thisRequest !== requestNumber) return;
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => load(termId)));
    } finally {
      if (thisRequest === requestNumber) body.removeAttribute('aria-busy');
    }
  }

  async function dismissCard(id) {
    try {
      await post(`/api/insights/${encodeURIComponent(id)}/dismiss`);
      if (state.term) load(state.term.id);
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    }
  }

  function renderInsights() {
    const { instructors, rooms, suggestions } = state.insights;

    // 1. Suggestions panel (Advisory suggestions & positive confirmations)
    const activeSuggestions = suggestions.filter((s) => !s.dismissed);
    const suggestionCards = activeSuggestions.map((s) => {
      const isInstructor = s.axis === 'INSTRUCTOR_UTILIZATION';
      const typeBadge = isInstructor
        ? el('span', { class: 'tag insight-tag' }, 'Instructor load')
        : el('span', { class: 'tag insight-tag' }, s.measure === 'OCCUPANCY' ? 'Room occupancy' : 'Room fill');

      const alreadyFlaggedBadge = s.alreadyFlagged
        ? el('a', {
            class: 'tag insight-conflict-tag',
            href: '#conflicts',
            title: 'An operational conflict is already open for this resource. Click to view.',
          }, 'Already flagged in Conflicts →')
        : null;

      const dismissBtn = el('button', {
        class: 'btn quiet small',
        type: 'button',
        onclick: () => dismissCard(s.id),
      }, 'Dismiss suggestion');

      return el('div', { class: 'insight-card' },
        el('div', { class: 'insight-card-header' },
          el('div', { class: 'insight-card-titles' },
            el('strong', {}, s.resourceName || 'Resource'),
            typeBadge,
            alreadyFlaggedBadge),
          dismissBtn),
        el('p', { class: 'insight-card-message' }, s.message));
    });

    const suggestionsPanel = el('section', { class: 'panel insight-panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, 'Advisory suggestions'),
        el('span', { class: 'meta' }, activeSuggestions.length === 1 ? '1 suggestion' : `${activeSuggestions.length} suggestions`)),
      el('div', { class: 'panel-body' },
        activeSuggestions.length === 0
          ? el('p', { class: 'hint' }, 'No active suggestions for this term. Schedule utilization is healthy!')
          : el('div', { class: 'insight-cards-grid' }, suggestionCards)));

    // 2. Instructor Utilization Section (FR5.1)
    const instructorRows = instructors.map((inst) => {
      let statusTag = el('span', { class: 'tag' }, 'Healthy');
      if (inst.status === 'UNDERUSED') {
        statusTag = el('span', { class: 'tag insight-alert' }, 'Under-used (<50%)');
      } else if (inst.status === 'CONFIRMATION') {
        statusTag = el('span', { class: 'tag insight-success' }, 'Full load (≥80%)');
      }

      const percent = Math.min(Math.round(inst.ratioPercent), 100);
      const barColor = inst.status === 'UNDERUSED' ? 'var(--color-amber, #d97706)' : inst.status === 'CONFIRMATION' ? 'var(--color-green, #16a34a)' : 'var(--color-blue, #2563eb)';

      const progressBar = el('div', { class: 'progress-track' },
        el('div', { class: 'progress-fill', style: `width: ${percent}%; background: ${barColor};` }));

      return el('tr', {},
        el('td', {}, el('strong', {}, inst.instructorName)),
        el('td', {}, `${inst.scheduledHours} h of ${inst.declaredHours} h`),
        el('td', {},
          el('div', { class: 'progress-wrapper' },
            progressBar,
            el('span', { class: 'progress-label' }, `${Math.round(inst.ratioPercent)}%`))),
        el('td', {}, statusTag));
    });

    const instructorPanel = el('section', { class: 'panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, 'Instructor schedule utilization'),
        el('span', { class: 'meta' }, `${instructors.length} declared`)),
      el('div', { class: 'scroll-x' },
        el('table', { class: 'cards' },
          el('thead', {},
            el('tr', {},
              el('th', { scope: 'col' }, 'Instructor'),
              el('th', { scope: 'col' }, 'Scheduled / Declared'),
              el('th', { scope: 'col' }, 'Utilization'),
              el('th', { scope: 'col' }, 'Status'))),
          el('tbody', {}, instructorRows))));

    // 3. Room Utilization Section (FR5.2)
    const roomRows = rooms.map((rm) => {
      let statusTag = el('span', { class: 'tag insight-success' }, 'Well utilized');
      if (rm.status === 'UNDERUSED') {
        statusTag = el('span', { class: 'tag insight-alert' }, 'Room underused');
      } else if (rm.status === 'OVERSIZED') {
        statusTag = el('span', { class: 'tag insight-amber' }, 'Oversized');
      }

      return el('tr', {},
        el('td', {}, el('strong', {}, rm.roomName), el('div', { class: 'muted' }, `Capacity: ${rm.capacity}`)),
        el('td', {}, `${rm.scheduledHours} h / week`),
        el('td', {}, `${Math.round(rm.occupancyPercent)}%`),
        el('td', {}, `${Math.round(rm.fillPercent)}%`),
        el('td', {}, statusTag));
    });

    const roomPanel = el('section', { class: 'panel' },
      el('div', { class: 'panel-head' },
        el('h2', {}, 'Room occupancy & fill'),
        el('span', { class: 'meta' }, `${rooms.length} rooms`)),
      el('div', { class: 'scroll-x' },
        el('table', { class: 'cards' },
          el('thead', {},
            el('tr', {},
              el('th', { scope: 'col' }, 'Room'),
              el('th', { scope: 'col' }, 'Weekly scheduled'),
              el('th', { scope: 'col' }, 'Occupancy (of 50h)'),
              el('th', { scope: 'col' }, 'Avg fill'),
              el('th', { scope: 'col' }, 'Status'))),
          el('tbody', {}, roomRows))));

    body.replaceChildren(suggestionsPanel, el('div', { class: 'two-col' }, instructorPanel, roomPanel));
  }

  async function start() {
    try {
      const terms = await getJson('/api/terms');
      state.terms = terms;
    } catch (error) {
      body.replaceChildren();
      messages.replaceChildren(banner(error.message, 'Try again', () => { messages.replaceChildren(); start(); }));
      return;
    }
    if (!state.terms.length) {
      body.replaceChildren(el('section', { class: 'panel' }, el('div', { class: 'empty' },
        el('strong', {}, 'No terms yet'),
        'Create a term in Timetable to see optimization insights.')));
      return;
    }
    termSelect.replaceChildren(...[...state.terms].sort((a, b) => b.startDate.localeCompare(a.startDate))
      .map((t) => el('option', { value: t.id }, termLabel(t))));
    const chosen = state.terms.find((t) => t.id === chosenTermId()) ?? state.terms[0];
    state.term = chosen;
    showTopbar(chosen);
    load(chosen.id);
  }

  start();
  return el('div', { class: 'content' }, messages, body);
}
