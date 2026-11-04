import { download, upload } from './api.js';
import { banner, el } from './dom.js';
import { field, input, select } from './forms.js';

const MAX_FILE_BYTES = 1024 * 1024; // Spring's multipart limit

const TYPES = {
  'ready-made-schedule': {
    label: 'Ready-made schedule (room and time already assigned)',
    hint: 'Its rooms, subjects, instructors and a Draft term must already exist. Import rooms and the subject list first. Example row: HIST 120,Modern World History,MW,10:00,10:50,Founders 110,L. Bianchi,Fall 2026',
  },
  'subject-list': {
    label: 'Subject list (not yet scheduled)',
    hint: 'Adds subjects to the catalog. Room type is Lecture hall, Lab or Seminar room. Example row: CS 450,Advanced Algorithms,3,Lecture hall,Projector',
  },
  rooms: {
    label: 'Rooms',
    hint: 'Adds rooms to the catalog. Room type is Lecture hall, Lab or Seminar room, and equipment is separated by semicolons. Example row: Founders 110,Founders Hall,40,Seminar room,Whiteboard; Projector',
  },
  equipment: {
    label: 'Equipment',
    hint: 'Adds equipment to the catalog. Example row: Portable speaker,Audio equipment,3,"AV Storage, Hale Hall"',
  },
};

function resultsTable(batch) {
  return el('div', {},
    el('p', { class: 'result-count' }, `${batch.fileName}: ${batch.added} added, ${batch.needsReview} need${batch.needsReview === 1 ? 's' : ''} review`),
    el('table', { class: 'cards' },
      el('thead', {}, el('tr', {}, ['Data row', 'Result', 'What to fix'].map((name) => el('th', { scope: 'col' }, name)))),
      el('tbody', {}, batch.rows.map((row) => el('tr', {},
        el('td', { 'data-label': 'Data row' }, String(row.rowNumber)),
        el('td', { 'data-label': 'Result' }, row.outcome === 'ADDED'
          ? el('span', { class: 'tag ok' }, 'Added')
          : el('span', { class: 'tag draft' }, 'Needs review')),
        el('td', { 'data-label': 'What to fix' }, row.reason ?? '—'))))));
}

/** Template download, file upload and row-by-row results. onImported redraws whatever the import may have added to. */
export function importPanel(onImported) {
  const type = select('type', Object.entries(TYPES).map(([value, { label }]) => [value, label]), 'ready-made-schedule', { onchange: showType });
  const file = input('file', { type: 'file', accept: '.csv,text/csv', required: true });
  const hint = el('span', { class: 'hint' });
  const submit = el('button', { class: 'btn primary', type: 'submit' }, 'Upload');
  const results = el('div', { 'aria-live': 'polite' });

  function showError(message) {
    results.replaceChildren(el('div', { class: 'panel-body' }, banner(message)));
  }

  /** The previous results answer a different question once the type changes. */
  function showType() {
    hint.textContent = TYPES[type.value].hint;
    results.replaceChildren();
  }

  async function downloadTemplate() {
    try {
      await download(`/api/import/templates/${type.value}`, `${type.value}-template.csv`);
    } catch (error) {
      showError(error.message);
    }
  }

  async function send(event) {
    event.preventDefault();
    const chosen = file.files[0];
    if (chosen.size > MAX_FILE_BYTES) {
      showError(`${chosen.name} is larger than the 1 MB limit. Split it into smaller files.`);
      return;
    }
    submit.disabled = true;
    try {
      const batch = await upload(`/api/import/${type.value}`, chosen);
      results.replaceChildren(batch.rows.length
        ? resultsTable(batch)
        : el('div', { class: 'empty' }, el('strong', {}, `${batch.fileName} has no rows to import`), 'It only has a header row.'));
      file.value = ''; // so the corrected file can be chosen and sent next
      onImported();
    } catch (error) {
      showError(error.message);
    } finally {
      submit.disabled = false;
    }
  }

  showType();
  return el('section', { class: 'panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, 'Import from file'),
      el('span', { class: 'meta' }, 'A wrong header rejects the file. A bad row is listed below, and the other rows still save. Row 1 is the first row under the header.')),
    el('form', { class: 'toolbar import-form', onsubmit: send },
      el('div', { class: 'wide-field' }, field('What are you importing?', type), hint),
      field('CSV file', file),
      el('button', { class: 'btn', type: 'button', onclick: downloadTemplate }, 'Download template'),
      submit),
    results);
}
