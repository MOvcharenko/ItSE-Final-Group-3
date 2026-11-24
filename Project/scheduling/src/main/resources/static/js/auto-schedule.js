import { banner, dataTable, el } from './dom.js';
import { field, select } from './forms.js';
import { meetingLines, plural } from './labels.js';
import { api } from './scheduling-api.js';

const PATTERN_OPTIONS = [
  ['AUTO', 'Auto (MWF / TTh)'],
  ['MWF', 'MWF (3x50m)'],
  ['TTH', 'TTh (2x75m)'],
  ['MW', 'MW (2x75m / 100m)'],
  ['SINGLE', 'Once weekly'],
];

function buildDefaultItems(subjects, sections, keepExisting) {
  const scheduledSubjectIds = new Set(sections.map((section) => section.subjectId));
  return subjects.map((subject) => ({
    subject,
    selected: keepExisting ? !scheduledSubjectIds.has(subject.id) : true,
    instructorId: '',
    patternStyle: 'AUTO',
  }));
}

/**
 * Opens the SAT/MaxSAT Auto-Fill Schedule modal for a Draft term.
 * Keeps generated options in browser memory so the Scheduler can compare Option 1, Option 2, ...
 * before manually clicking Apply to commit to the Draft term.
 */
export function openAutoScheduleModal({ term, subjects, sections, instructors, onApplied }) {
  let step = 'configure'; // 'configure' | 'preview'
  let keepExistingFixed = true;
  const preferences = {
    avoidSplitDays: true,
    preferPrimeHours: true,
    respectPreferences: true,
    optimizeRoomFit: true,
  };
  const itemRows = buildDefaultItems(subjects, sections, keepExistingFixed);
  const generatedOptions = [];
  let activeOption = 0;
  let lastRequestBody = null;
  let busy = false;

  const sortedInstructors = [...instructors].sort((a, b) => a.name.localeCompare(b.name));
  const messages = el('div', { class: 'messages' });
  const bodyBox = el('div', { class: 'form-body' });
  const actionsBox = el('div', { class: 'form-actions' });
  const titleId = `auto-schedule-title-${Date.now()}`;

  const dialog = el('dialog', {
    class: 'dialog dialog-wide',
    'aria-labelledby': titleId,
    onclose: () => dialog.remove(),
  },
  el('h2', { id: titleId }, `Auto-Fill Schedule · ${term.name}`),
  el('p', { class: 'muted' },
    'Generates a conflict-free Draft timetable using Weighted MaxSAT while optimizing instructor convenience and room fit.'),
  messages,
  bodyBox,
  actionsBox);

  function render() {
    if (step === 'configure') {
      renderConfigure();
    } else {
      renderPreview();
    }
  }

  function renderConfigure() {
    const modeField = el('div', { class: 'auto-mode-box' },
      el('label', { class: 'check' },
        el('input', {
          type: 'checkbox',
          checked: keepExistingFixed,
          disabled: busy,
          onchange: (event) => {
            keepExistingFixed = event.target.checked;
            const scheduledIds = new Set(sections.map((section) => section.subjectId));
            itemRows.forEach((row) => {
              row.selected = keepExistingFixed ? !scheduledIds.has(row.subject.id) : true;
            });
            render();
          },
        }),
        `Keep ${plural(sections.length, 'existing section')} fixed and schedule around them (uncheck to rebuild entire Draft)`));

    const prefToggles = el('fieldset', { class: 'group' },
      el('legend', {}, 'Convenience & Optimization Preferences (MaxSAT Soft Weights)'),
      el('div', { class: 'checks auto-prefs' },
        prefCheck('avoidSplitDays', 'Avoid split days (e.g., 09:00 & 18:00)'),
        prefCheck('preferPrimeHours', 'Prefer prime daytime hours (09:00–17:00)'),
        prefCheck('respectPreferences', 'Respect instructor PREFER blocks'),
        prefCheck('optimizeRoomFit', 'Optimize room size fit')));

    const selectAllRow = el('div', { class: 'auto-select-bar' },
      el('span', { class: 'muted' }, `${itemRows.filter((row) => row.selected).length} of ${plural(itemRows.length, 'subject')} selected`),
      el('button', {
        class: 'btn quiet small',
        type: 'button',
        disabled: busy,
        onclick: () => { itemRows.forEach((row) => { row.selected = true; }); render(); },
      }, 'Select all'),
      el('button', {
        class: 'btn quiet small',
        type: 'button',
        disabled: busy,
        onclick: () => { itemRows.forEach((row) => { row.selected = false; }); render(); },
      }, 'Clear'));

    const scheduledIds = new Set(sections.map((section) => section.subjectId));
    const subjectTable = el('div', { class: 'scroll-x auto-subjects' },
      el('table', {},
        el('thead', {}, el('tr', {},
          ['Use', 'Subject', 'Credits / Room', 'Instructor', 'Weekly Pattern'].map((label) => el('th', { scope: 'col' }, label)))),
        el('tbody', {}, itemRows.map((row) => {
          const alreadyInTerm = scheduledIds.has(row.subject.id);
          const instPick = select('inst', [['', 'Auto (Department)'], ...sortedInstructors.map((p) => [p.id, p.name])],
            row.instructorId, { disabled: busy, onchange: () => { row.instructorId = instPick.value; } });
          const patPick = select('pat', PATTERN_OPTIONS, row.patternStyle,
            { disabled: busy, onchange: () => { row.patternStyle = patPick.value; } });
          return el('tr', {},
            el('td', {}, el('input', {
              type: 'checkbox',
              checked: row.selected,
              disabled: busy,
              'aria-label': `Include ${row.subject.courseCode}`,
              onchange: (event) => {
                row.selected = event.target.checked;
                render();
              },
            })),
            el('td', {},
              el('strong', {}, row.subject.courseCode),
              ` ${row.subject.title}`,
              alreadyInTerm && keepExistingFixed && el('span', { class: 'tag draft', style: 'margin-left:6px' }, 'Already in term')),
            el('td', { class: 'muted' }, `${row.subject.creditHours} cr · ${row.subject.defaultRoomType}`),
            el('td', {}, instPick),
            el('td', {}, patPick));
        }))));

    bodyBox.replaceChildren(modeField, prefToggles, selectAllRow, subjectTable);

    actionsBox.replaceChildren(
      el('button', { class: 'btn', type: 'button', disabled: busy, onclick: () => dialog.close() }, 'Cancel'),
      el('button', {
        class: 'btn primary',
        type: 'button',
        disabled: busy,
        onclick: () => runPreview(false),
      }, busy ? 'Solving…' : 'Generate Schedule'));
  }

  function prefCheck(key, label) {
    return el('label', { class: 'check' },
      el('input', {
        type: 'checkbox',
        checked: preferences[key],
        disabled: busy,
        onchange: (event) => { preferences[key] = event.target.checked; },
      }),
      label);
  }

  function renderPreview() {
    const current = generatedOptions[activeOption];
    if (!current) {
      step = 'configure';
      render();
      return;
    }

    const tabs = el('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Generated schedule options' },
      generatedOptions.map((option, index) => el('button', {
        type: 'button',
        role: 'tab',
        'aria-selected': String(index === activeOption),
        disabled: busy,
        onclick: () => {
          activeOption = index;
          messages.replaceChildren();
          renderPreview();
        },
      }, `Option ${index + 1}`, option.solvable && el('span', { class: 'count' }, `${option.summary.convenienceScore}/100`))));

    if (!current.solvable) {
      bodyBox.replaceChildren(
        tabs,
        el('div', { class: 'empty' },
          el('strong', {}, current.status === 'NO_MORE_OPTIONS' ? 'No more distinct options available' : 'No conflict-free schedule found'),
          el('ul', { class: 'auto-diag-list' },
            (current.diagnostics || []).map((msg) => el('li', {}, msg)))));

      actionsBox.replaceChildren(
        el('button', { class: 'btn', type: 'button', disabled: busy, onclick: () => { step = 'configure'; render(); } }, 'Back to settings'),
        el('button', { class: 'btn', type: 'button', disabled: busy, onclick: () => dialog.close() }, 'Close'));
      return;
    }

    const s = current.summary;
    const summaryStrip = el('div', { class: 'auto-summary' },
      metric('Convenience', `${s.convenienceScore}/100`),
      metric('Conflicts', '0'),
      metric('Split Days (6h+)', String(s.splitDayCount)),
      metric('Pref. Matched', `${s.preferredSlotsMatched}/${s.totalPlaced}`),
      metric('Edge Hours', String(s.edgeHourSections)),
      metric('SAT Solver', `${s.solveTimeMs} ms (${s.satVariables} vars, ${s.satClauses} clauses)`));

    const table = dataTable([
      { label: 'Course', cell: (p) => [el('strong', {}, p.courseCode), ` · ${p.sectionNumber}`] },
      { label: 'Room', cell: (p) => [p.roomName, p.roomFillPercent > 0 && el('span', { class: 'tag', style: 'margin-left:6px' }, `${p.roomFillPercent}% fill`)] },
      { label: 'Instructor', cell: (p) => [p.instructorName, p.matchedPreference && el('span', { class: 'tag published', style: 'margin-left:6px' }, 'Preferred')] },
      { label: 'Weekly Schedule', cell: (p) => meetingLines(p.meetings).map((line) => el('div', { class: 'nowrap' }, line)) },
    ], current.placements);

    bodyBox.replaceChildren(tabs, summaryStrip, el('div', { class: 'scroll-x' }, table));

    actionsBox.replaceChildren(
      el('button', { class: 'btn', type: 'button', disabled: busy, onclick: () => { step = 'configure'; messages.replaceChildren(); render(); } }, 'Back to settings'),
      el('button', {
        class: 'btn',
        type: 'button',
        disabled: busy,
        onclick: () => runPreview(true),
      }, busy ? 'Finding another…' : 'Try Another Option'),
      el('button', {
        class: 'btn primary',
        type: 'button',
        disabled: busy,
        onclick: () => applyOption(current),
      }, busy ? 'Applying…' : `Apply Option ${activeOption + 1} to Draft`));
  }

  function metric(label, value) {
    return el('div', { class: 'auto-metric' },
      el('span', { class: 'muted' }, label),
      el('strong', {}, value));
  }

  async function runPreview(isAlternative) {
    const selected = itemRows.filter((row) => row.selected);
    if (!selected.length) {
      messages.replaceChildren(banner('Select at least one subject to auto-schedule.'));
      return;
    }

    const excludedSolutions = isAlternative
      ? generatedOptions
        .filter((opt) => opt.solvable)
        .map((opt) => opt.placements.map((p) => ({
          subjectId: p.subjectId,
          sectionNumber: p.sectionNumber,
          roomId: p.roomId,
          instructorId: p.instructorId,
          patternId: p.patternId,
        })))
      : [];

    if (!isAlternative) {
      generatedOptions.length = 0;
      activeOption = 0;
      lastRequestBody = {
        keepExistingFixed,
        ...preferences,
        items: selected.map((row) => ({
          subjectId: row.subject.id,
          sectionNumber: null,
          instructorIds: row.instructorId ? [row.instructorId] : [],
          patternStyle: row.patternStyle,
        })),
      };
    }

    const requestBody = { ...lastRequestBody, excludedSolutions };
    busy = true;
    messages.replaceChildren();
    render();
    try {
      const result = await api.previewAutoSchedule(term.id, requestBody);
      if (isAlternative && !result.solvable && result.status === 'NO_MORE_OPTIONS') {
        messages.replaceChildren(banner(result.diagnostics?.[0] || 'No other distinct conflict-free options exist.'));
        return;
      }
      generatedOptions.push(result);
      activeOption = generatedOptions.length - 1;
      step = 'preview';
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    } finally {
      busy = false;
      render();
    }
  }

  async function applyOption(option) {
    busy = true;
    messages.replaceChildren();
    render();
    try {
      await api.applyAutoSchedule(term.id, {
        replaceExisting: !keepExistingFixed,
        placements: option.placements.map((p) => ({
          subjectId: p.subjectId,
          sectionNumber: p.sectionNumber,
          roomId: p.roomId,
          instructorIds: [p.instructorId],
          meetings: p.meetings,
          equipment: p.equipment,
        })),
      });
      dialog.close();
      await onApplied(option.placements.length);
    } catch (error) {
      messages.replaceChildren(banner(error.message));
    } finally {
      busy = false;
      if (dialog.open) render();
    }
  }

  render();
  document.getElementById('app').append(dialog);
  dialog.showModal();
}
