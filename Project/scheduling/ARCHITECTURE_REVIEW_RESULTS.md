# Architecture Review Results

> Analyzed on: 2026-11-07
> Project: `Project/scheduling/src/main/resources/static` (Academic Scheduling web UI)
> Total components analyzed: 17 JavaScript modules (12 screens and panels, 5 shared modules), 2 HTML pages
> Issues found: 13 (2 are bugs)

**Note on the stack:** this UI is plain JavaScript modules with no framework or build step. Elements are built with
`el(tag, props, ...children)` from `dom.js`. "Component" here means a function that returns an element, such as
`catalogScreen` or `noticesPanel`. React-specific advice doesn't apply, so the recommended code below uses the
project's own `el()` style.

## Summary

The application shell is centralised in `app.js`, every screen has the same `(user, setTopbar)` signature, and the
shared pieces (`el`, `openForm`, `field`, the `dates.js` helpers) are small and well named. The biggest problem is
the **term picker, which is copied into five screens**. One of the copies has already drifted into a bug (ISSUE-01).
The next most important problems:

- **No API layer above raw HTTP:** endpoint strings are spread across 11 files.
- **Screens import helpers from other screens.**
- **Two Phase 5 functions are oversized:** `insightsScreen` and the My Teaching availability panel each mix several
  levels of abstraction.

## Issues

### ISSUE-01: Insights reloads the wrong term after "Dismiss suggestion"

**Severity**: High
**Principle**: Unclear Data Flow
**Location**: `js/insights.js` (Dev B)

`state.term` is set once in `start()`, and choosing another term doesn't update it. Dismissing a suggestion then
reloads the term the screen opened with: the picker shows the new term, but the cards and tables switch back to the
old one. The term is kept in two places that can disagree: the picker's value and `state.term`.

#### Current (Bad)

```js
function selectTerm(id) {
  rememberTerm(id);
  const chosen = state.terms.find((t) => t.id === id);
  if (chosen) showTopbar(chosen);
  load(id);                                   // state.term is not updated
}

async function dismissCard(id) {
  await post(`/api/insights/${encodeURIComponent(id)}/dismiss`);
  if (state.term) load(state.term.id);        // still the first term
}
```

#### Recommended (Good)

```js
function selectTerm(id) {
  state.term = state.terms.find((t) => t.id === id);
  rememberTerm(id);
  showTopbar(state.term);
  load(state.term.id);
}
```

**Why this is better**: there is one current term, and both loading and dismissing read it. `conflicts.js` and
`timetable.js` already work this way, and ISSUE-02 prevents this kind of drift for good.

---

### ISSUE-02: The term picker is copied into five screens

**Severity**: High
**Principle**: Code Duplication
**Location**: `js/calendar.js`, `js/conflicts.js`, `js/insights.js`, `js/teaching.js`, `js/timetable.js`

Each screen builds its own term `<select>` and Draft/Published tag, and each has its own copy of these steps:

- sort the terms newest first
- label Drafts as "(Draft)"
- choose the starting term with `chosenTermId()`, falling back to a default
- `rememberTerm()` the choice

The copies already differ. Calendar and My Teaching fall back to `defaultTerm()`, while Conflicts and Insights fall
back to `terms[0]`, so the same tab opens different terms on different screens. Insights also forgets to update its
state (ISSUE-01).

#### Current (Bad)

```js
// insights.js — the same block, almost word for word, is in calendar.js, conflicts.js and teaching.js
const termSelect = el('select', { 'aria-label': 'Term', onchange: () => selectTerm(termSelect.value) });
const termTag = el('span', { class: 'tag' });
function showTopbar(term) {
  const draft = term.status === 'DRAFT';
  termTag.className = draft ? 'tag draft' : 'tag published';
  termTag.textContent = draft ? 'Draft' : 'Published';
  termSelect.value = term.id;
  setTopbar([termSelect, termTag], [/* screen's own buttons */]);
}
// ...
termSelect.replaceChildren(...[...state.terms].sort((a, b) => b.startDate.localeCompare(a.startDate))
  .map((t) => el('option', { value: t.id }, termLabel(t))));
const chosen = state.terms.find((t) => t.id === chosenTermId()) ?? state.terms[0];
```

#### Recommended (Good)

```js
// terms.js — one owner for how a term is chosen and shown
export function termPicker(terms, onChange) {
  const select = el('select', { 'aria-label': 'Term', onchange: () => choose(select.value) },
    sortNewestFirst(terms).map((term) => el('option', { value: term.id }, termLabel(term))));
  const tag = el('span', { class: 'tag' });
  function choose(id) {
    const term = terms.find((item) => item.id === id);
    select.value = term.id;
    tag.className = term.status === 'DRAFT' ? 'tag draft' : 'tag published';
    tag.textContent = term.status === 'DRAFT' ? 'Draft' : 'Published';
    rememberTerm(term.id);
    onChange(term);
  }
  return { controls: [select, tag], start: () => choose(initialTerm(terms).id) };
}

// insights.js
const picker = termPicker(terms, (term) => { state.term = term; load(term.id); });
setTopbar(picker.controls, [openTimetableLink, viewConflictsLink]);
picker.start();
```

**Why this is better**: there's one rule for which term opens and one way to switch, and a screen can't half-update
its own copy.

---

### ISSUE-03: No API layer above raw HTTP, and `api.js` does four jobs

**Severity**: High
**Principle**: Missing API Abstraction
**Location**: `js/api.js`, and every screen

Screens build endpoint URLs themselves: `'/api/terms'` appears in 5 files, and
``/api/terms/${encodeURIComponent(id)}/...`` appears in many more. Renaming an endpoint means searching 11 files,
and nothing can be tested without a running server. Meanwhile, `api.js` mixes four concerns:

- HTTP and error handling (`authorized`, `getJson`, `post`)
- sign-in (`signIn`, `startOidcSignIn`, PKCE)
- logout
- tab navigation memory (`rememberTerm`, `openSectionNext`, `takeSectionToOpen`)

#### Current (Bad)

```js
// timetable.js
const [terms, subjects, rooms, equipment, instructors] = await Promise.all(
  ['/api/terms', '/api/subjects', '/api/rooms', '/api/equipment', '/api/instructors'].map(getJson));
// ...
await put(`/api/terms/${encodeURIComponent(term.id)}/sections/${encodeURIComponent(section.id)}`, { /* ... */ });
```

#### Recommended (Good)

```js
// scheduling-api.js — owns every URL; the HTTP functions are passed in, so a test can pass fakes
export class SchedulingApi {
  constructor({ getJson, post, put, patch, del }) { Object.assign(this, { http: { getJson, post, put, patch, del } }); }
  terms() { return this.http.getJson('/api/terms'); }
  sections(termId) { return this.http.getJson(`/api/terms/${id(termId)}/sections`); }
  saveSection(termId, sectionId, body) { return this.http.put(`/api/terms/${id(termId)}/sections/${id(sectionId)}`, body); }
  // ...
}
const id = encodeURIComponent;
export const api = new SchedulingApi(http);   // http.js keeps authorized/getJson/post as they are today

// timetable.js
const [terms, subjects] = await Promise.all([api.terms(), api.subjects()]);
```

Split the rest of `api.js` by job:

- `http.js`: transport and error messages
- `auth.js`: dev sign-in, the OIDC flow and logout
- `session.js`: the term and section remembered in this tab

**Why this is better**: screens talk in domain terms, URLs live in one file, and a test can construct
`new SchedulingApi(fakeHttp)` without a server.

---

### ISSUE-04: Screens import helpers from other screens

**Severity**: Medium
**Principle**: Proper Layering
**Location**: `js/teaching.js`, `js/conflicts.js`, `js/search.js`, `js/timetable-grid.js`

These shared helpers live inside screen modules:

| Helper | Lives in | Used by |
|---|---|---|
| `defaultTerm` | `calendar.js` | `teaching.js` |
| `layoutOverlaps` | `calendar.js` | `timetable-grid.js` |
| `meetingLabel` | `timetable.js` | `conflicts.js`, `teaching.js` |
| `ROOM_TYPES`, `roomTypeLabel` | `catalog.js` | `search.js` |

So loading My Teaching pulls in the whole Calendar and Timetable screens, and changing a screen can break another
screen that only wanted a label.

#### Current (Bad)

```js
// teaching.js
import { defaultTerm } from './calendar.js';
import { meetingLabel } from './timetable.js';
```

#### Recommended (Good)

```js
// labels.js — domain wording shared by screens: meetingLabel, roomTypeLabel, ROOM_TYPES, termLabel
// terms.js  — defaultTerm/initialTerm and termPicker (ISSUE-02)
// layout.js — layoutOverlaps, used by both time grids
import { meetingLabel } from './labels.js';
import { initialTerm } from './terms.js';
```

**Why this is better**: screens depend only on shared modules and never on each other, so each screen file can
change on its own.

---

### ISSUE-05: `renderInsights` builds three panels inline in one 115-line function

**Severity**: Medium
**Principle**: SLA Violation
**Location**: `js/insights.js` (Dev B)

The function's own numbered comments (`// 1. Suggestions panel`, `// 2. Instructor Utilization`, `// 3. Room
Utilization`) name the parts it should be built from. It mixes four levels of abstraction:

- the panels
- the table headers, written out one `el('th')` at a time
- the status-to-label rules
- arithmetic for the progress bars

#### Current (Bad)

```js
const instructorRows = instructors.map((inst) => {
  let statusTag = el('span', { class: 'tag' }, 'Healthy');
  if (inst.status === 'UNDERUSED') { statusTag = el('span', { class: 'tag insight-alert' }, 'Under-used (<50%)'); }
  // ...
  const percent = Math.min(Math.round(inst.ratioPercent), 100);
  const progressBar = el('div', { class: 'progress-track' }, el('div', { class: 'progress-fill', style: `width: ${percent}%; ...` }));
  return el('tr', {}, /* four cells */);
});
const instructorPanel = el('section', { class: 'panel' }, /* head, table, 4 hand-written th */);
```

#### Recommended (Good)

```js
function renderInsights() {
  const { instructors, rooms, suggestions } = state.insights;
  body.replaceChildren(
    suggestionsPanel(suggestions.filter((s) => !s.dismissed), dismissCard),
    el('div', { class: 'two-col' }, instructorUtilizationPanel(instructors), roomUtilizationPanel(rooms)));
}
// each panel is its own function, built from instructorRow(inst) / roomRow(room) / suggestionCard(s, onDismiss)
```

**Why this is better**: `renderInsights` can be read in one sentence using only the names of its parts, and each row
type can be changed without scrolling past the other two panels.

---

### ISSUE-06: The My Teaching availability panel does five jobs in one 135-line function

**Severity**: Medium
**Principle**: SLA Violation
**Location**: `js/teaching.js`, `availabilityPanel` (Dev B)

This one function does all of these:

- builds the slot grid
- cycles cell states
- totals the hours
- builds the equipment checkboxes and the notes field
- saves

It also has the same name as `search.js`'s `availabilityPanel`, which means something quite different (finding free
rooms), so a reader can't tell from a stack trace or a search which one is meant.

#### Current (Bad)

```js
function availabilityPanel(termId, availabilityData, onSaved) {
  const blocksMap = new Map();
  // ... 20 lines: grid table with an inline cell button and click handler
  function updateSummary() { let openH = 0, preferH = 0, unavailH = 0; /* nested loops */ }
  const equipCheckboxes = PORTABLE_EQUIPMENT.map((item) => { /* ... */ });
  const saveBtn = el('button', { onclick: async () => { /* builds blocks with the same nested loops again */ } });
  // ...
}
```

#### Recommended (Good)

```js
/** The instructor's declaration for one term: when they can teach, what they need, and a note (UC-14). */
function availabilityDeclarationPanel(termId, declaration, onSaved) {
  const grid = availabilityGrid(declaration.blocks, { readOnly: declaration.readOnly, onChange: () => summary.update(grid.blocks()) });
  const summary = availabilitySummary(grid.blocks());
  const needs = equipmentNeeds(declaration.equipmentNeeds, { readOnly: declaration.readOnly });
  const notes = notesField(declaration.notes, { readOnly: declaration.readOnly });
  return declarationForm({ readOnly: declaration.readOnly, parts: [grid, summary, needs, notes],
    onSave: () => api.saveAvailability(termId, { blocks: grid.blocks(), equipmentNeeds: needs.value(), notes: notes.value() }).then(onSaved) });
}
```

**Why this is better**: the grid owns its cells and the list of blocks, so the totals and the save read
`grid.blocks()` instead of each repeating the day-by-slot loops.

---

### ISSUE-07: "Saved" appears as a red error banner with a button that does nothing

**Severity**: Medium
**Principle**: Poor Component API
**Location**: `js/teaching.js:187`

`banner(message, actionLabel, onAction)` is the red alert used for failures. My Teaching passes `'success'` as the
second argument, so a successful save shows as an error, with a button labelled "success" that has no click handler.
The `banner` API only offers one kind of message, so callers improvise.

#### Current (Bad)

```js
messageEl.replaceChildren(banner('Availability declaration and equipment requests saved successfully!', 'success'));
```

#### Recommended (Good)

```js
// the Conflicts and Timetable screens already report success this way: a polite status line
const status = el('p', { class: 'status', role: 'status' });
// ...
status.textContent = 'Availability and equipment needs saved.';
```

**Why this is better**: success goes through the same status line the other screens use, and `banner` keeps one
meaning: something went wrong.

---

### ISSUE-08: My Teaching offers a fixed equipment list instead of the catalog

**Severity**: Medium
**Principle**: Unclear Data Flow
**Location**: `js/teaching.js`, `PORTABLE_EQUIPMENT`

The instructor picks from `['Projector', 'Laptop cart', 'Mic kit', 'Document camera']`, written into the page. It
isn't the equipment the Scheduler manages on the Catalog screen. An item added there never appears, and a name here
may match nothing the Scheduler can book.

#### Current (Bad)

```js
const PORTABLE_EQUIPMENT = ['Projector', 'Laptop cart', 'Mic kit', 'Document camera'];
// ...
const equipCheckboxes = PORTABLE_EQUIPMENT.map((item) => { /* checkbox per fixed name */ });
```

#### Recommended (Good)

```js
// teachingScreen loads the catalog with the declaration and passes it down
const [teaching, declaration, equipment] = await Promise.all([api.teaching(termId), api.myAvailability(termId), api.equipment()]);
panels.push(availabilityDeclarationPanel(termId, declaration, equipment.map((item) => item.name), () => load(termId)));
```

**Why this is better**: the options come from the same data the Scheduler maintains, so what an instructor asks for
always exists. Check first whether an instructor may call `GET /api/equipment`: Bookings already loads it for
instructors, so it should be allowed.

---

### ISSUE-09: Data tables are written out by hand in nine files

**Severity**: Medium
**Principle**: Code Duplication
**Location**: `bookings.js`, `calendar.js`, `catalog.js`, `conflicts.js`, `import.js`, `insights.js`, `search.js`,
`teaching.js`, `timetable.js`

There are 12 copies of `el('thead', {}, el('tr', {}, columns.map((name) => el('th', { scope: 'col' }, name))))`. Two
local helpers already exist: `panel()` in `catalog.js` and `table()` in `search.js`. Some copies forget the
phone-friendly `data-label`, `scope: 'col'` or the `scroll-x` wrapper (Insights writes its `th` by hand; the teaching
grid has no `scope`).

#### Current (Bad)

```js
// conflicts.js (the same shape in eight other files)
el('table', { class: 'cards' },
  el('thead', {}, el('tr', {}, ['When', 'Conflict', 'Dismissed by', 'Reason'].map((name) => el('th', { scope: 'col' }, name)))),
  el('tbody', {}, dismissals.map((entry) => el('tr', {},
    el('td', { class: 'nowrap', 'data-label': 'When' }, dateTime(entry.dismissedAt)),
    // ...
```

#### Recommended (Good)

```js
// dom.js
/** columns: [{ label, cell(row), class? }]. Cards on phones: each cell is labelled with its column. */
export function dataTable(columns, rows, { cards = true } = {}) {
  return el('div', { class: 'scroll-x' }, el('table', { class: cards ? 'cards' : null },
    el('thead', {}, el('tr', {}, columns.map((column) => el('th', { scope: 'col' }, column.label)))),
    el('tbody', {}, rows.map((row) => el('tr', {}, columns.map((column) =>
      el('td', { class: column.class, 'data-label': column.label }, column.cell(row))))))));
}

// conflicts.js
dataTable([
  { label: 'When', class: 'nowrap', cell: (entry) => dateTime(entry.dismissedAt) },
  { label: 'Conflict', cell: dismissedConflict },
  { label: 'Dismissed by', cell: (entry) => entry.dismissedByName },
  { label: 'Reason', cell: (entry) => entry.reason },
], dismissals);
```

**Why this is better**: each table is described by its columns, and accessibility and phone layout are set once for
every table.

---

### ISSUE-10: The "only the latest answer counts" logic is copied seven times, and missing where it's needed

**Severity**: Medium
**Principle**: Code Duplication
**Location**: `bookings.js`, `calendar.js`, `conflicts.js`, `insights.js`, `search.js`, `teaching.js`, `timetable.js`

Seven modules repeat `const thisRequest = ++requestNumber; ... if (thisRequest !== requestNumber) return;`, 33 lines
in all. The one place without it shows why it matters. In Timetable, "Show busy times for" fires a request on each
change with no guard. Choosing two instructors quickly can shade the first instructor's availability under the
second one's name, and a failure is swallowed silently (`catch { view.availabilityBlocks = []; }`).

#### Current (Bad)

```js
async function load() {
  const thisRequest = ++requestNumber;
  try {
    const view = await getJson(`/api/calendar?${params}`);
    if (thisRequest === requestNumber) showView(view);
  } catch (error) {
    if (thisRequest === requestNumber) { /* show error */ }
  }
}
```

#### Recommended (Good)

```js
// http.js
/** Wraps a loader so that an answer overtaken by a newer call is dropped, whether it worked or failed. */
export function latestOnly(loader) {
  let latest = 0;
  return async (...args) => {
    const mine = ++latest;
    try {
      const result = await loader(...args);
      if (mine !== latest) throw new Superseded();
      return result;
    } catch (error) {
      throw mine === latest ? error : new Superseded(); // an old call's failure is dropped too
    }
  };
}

// timetable.js
const loadAvailability = latestOnly((instructorId) => api.instructorAvailability(instructorId, state.term.id));
```

**Why this is better**: screens state the intent ("only the latest") instead of re-implementing it, and the busy-times
lens gets the same protection as everything else. The `Superseded` error is caught and ignored where screens already
handle errors.

---

### ISSUE-11: The shell and sign-in builders mix layout markup with logic

**Severity**: Low
**Principle**: SLA Violation
**Location**: `js/app.js`, `showApp`, `showSignIn`; `js/signin-callback.js`

The app shell is centralised, which is the right design: every screen gets the same sidebar and top bar through
`setTopbar`. But `showApp` builds the sidebar, navigation, user card, Account link and Switch-account button inline
next to the screen-choosing logic. And the landing block `el('main', { class: 'landing' }, el('div', { class:
'brand' }, h1, p), ...)` is repeated three times in `app.js` and once more in `signin-callback.js`.

#### Current (Bad)

```js
root.replaceChildren(el('main', { class: 'landing' },
  el('div', { class: 'brand' },
    el('h1', {}, 'Academic Scheduling'),
    el('p', {}, 'Opening sign-in…'))));
// ... the same shape twice more in showSignIn, and again in signin-callback.js
```

#### Recommended (Good)

```js
// shell.js
export const landing = (subtitle, ...content) => el('main', { class: 'landing' },
  el('div', { class: 'brand' }, el('h1', {}, 'Academic Scheduling'), el('p', {}, subtitle)), ...content);
export function appShell({ user, screens, current, accountUri, onSwitchAccount }) { /* sidebar + topbar + main */ }

// app.js
root.replaceChildren(landing('Opening sign-in…'));
root.replaceChildren(appShell({ user, screens, current: screen, accountUri: config?.accountUri, onSwitchAccount }));
```

**Why this is better**: `showApp` keeps only the decisions (which screens, which one is open), and the shell's markup
lives in one place.

---

### ISSUE-12: The same small helpers are written again in each file

**Severity**: Low
**Principle**: Code Duplication
**Location**: across `js/`

| Helper | Copies |
|---|---|
| Count with plural (`count`, `countLabel`, `sectionCount`, and inline `x === 1 ? '1 …' : …`) | 21 uses in 11 files |
| Code-to-label lookup (`ROOM_TYPES.find(...)`, `label(pairs, value)`, `RESOURCE_TYPES.find(...)[1]`, `DAYS.find(...)[1]`) | 4 versions |
| Day lists | 3 (`timetable.js`, `timetable-grid.js`, `teaching.js`) |
| `termLabel` | 3 copies, plus 2 inline |

#### Current (Bad)

```js
const countLabel = (count, one, many) => `${count} ${count === 1 ? one : many}`;    // conflicts.js
const sectionCount = (count) => (count === 1 ? '1 section' : `${count} sections`); // teaching.js
function count(items, noun) { return items.length === 1 ? `1 ${noun}` : `${items.length} ${noun}s`; } // catalog.js
```

#### Recommended (Good)

```js
// labels.js
export const plural = (count, one, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;
export const labelOf = (pairs, value) => pairs.find(([key]) => key === value)?.[1] ?? value;
export const DAYS = [['MONDAY', 'Monday'], /* ... */];
```

**Why this is better**: one helper per idea, so a wording or locale change is made once.

---

### ISSUE-13: Calendar rebuilds its own labelled select instead of using `forms.js`

**Severity**: Low
**Principle**: Code Duplication
**Location**: `js/calendar.js`, `filterField`

`forms.js` already has `field(label, control)` and `select(...)`, which link the label to its control. Calendar writes
its own version, `filterField`. Conflicts uses the shared ones for the same kind of filter.

#### Current (Bad)

```js
function filterField(id, label, onchange) {
  const select = el('select', { id, onchange });
  return { select, field: el('div', { class: 'field' }, el('label', { for: id }, label), select) };
}
```

#### Recommended (Good)

```js
const courseSelect = select('course', [['', 'All courses']], '', { onchange: load });
// ... toolbar: field('Course', courseSelect)
```

**Why this is better**: every form control gets its label and id the same way.

## Well-structured parts

- **`dom.js` / `forms.js`:** `el()` never inserts HTML, so names from the API can't inject markup. `openForm` keeps
  what was typed when a save fails and reports the error inside the dialog. Small, clear APIs.
- **`notices.js`, `import.js`, `search.js`:** each panel has one job, named inner functions (`noticeRow`,
  `resultsTable`, `roomTable`) and a documented return value (`search.js` returns `{ element, setCatalog, refresh }`).
- **`timetable-grid.js`:** `weekGrid({...})` takes plain data and two callbacks (`onOpen`, `onMove`) and knows nothing
  about the API. That's a good component boundary.
- **`dates.js`:** pure, documented helpers that avoid time-zone bugs on purpose.
- **App shell:** a single shell in `app.js` with a `setTopbar(details, actions)` slot. Screens never build their own
  navigation.

## Recommendations Summary

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | ISSUE-01: Insights reloads the wrong term | Low | High |
| 2 | ISSUE-07: "Saved" shown as an error banner | Low | Medium |
| 3 | ISSUE-02: Shared term picker | Medium | High |
| 4 | ISSUE-04: Move shared helpers out of screens | Low | Medium |
| 5 | ISSUE-08: Equipment from the catalog | Low | Medium |
| 6 | ISSUE-03: API layer, and split `api.js` | Medium | High |
| 7 | ISSUE-10: `latestOnly`, including the busy-times lens | Low | Medium |
| 8 | ISSUE-09: `dataTable` | Medium | Medium |
| 9 | ISSUE-05: Split `renderInsights` | Low | Medium |
| 10 | ISSUE-06: Split the availability panel | Medium | Medium |
| 11 | ISSUE-12: `labels.js` helpers | Low | Low |
| 12 | ISSUE-11: `landing` and `appShell` | Low | Low |
| 13 | ISSUE-13: Calendar filters via `forms.js` | Low | Low |

## Architecture Health Score

| Criterion | Score (1-5) | Notes |
|-----------|-------------|-------|
| Single Level of Abstraction | 3 | Most panels are clean; `renderInsights`, the availability panel and `showApp` mix levels |
| Component API Design | 4 | Consistent `(user, setTopbar)` screens and good callback APIs; `banner` is too narrow (ISSUE-07) |
| Data Flow Clarity | 3 | State is local to each screen, which is good, but the term is kept twice in Insights, and My Teaching uses a fixed list |
| API Abstraction Layer | 2 | One HTTP module, but URLs are in every screen and `api.js` does four jobs |
| App Layout / Shell | 4 | Centralised shell with a top-bar slot; markup could move out of `showApp` |
| Code Duplication | 2 | Term picker ×5, tables ×12, latest-answer guard ×7, count helpers ×21 |
| Composition Patterns | 4 | Small builders composed with `el()`; screens only depend on each other for a few helpers |
| **Overall** | **3** | Solid basics, with duplication to remove and two small bugs to fix first |
