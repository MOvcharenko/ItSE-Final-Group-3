// Choosing a term: one rule for which term a screen opens with, and one picker for switching, shared by every screen.
import { today } from './dates.js';
import { el } from './dom.js';
import { chosenTermId, rememberTerm } from './session.js';

export const termLabel = (term) => (term.status === 'DRAFT' ? `${term.name} (Draft)` : term.name);

export const sortNewestFirst = (terms) => [...terms].sort((a, b) => b.startDate.localeCompare(a.startDate));

/** The term to open with: the one running on date (Published first), else the next to start, else the latest. */
export function defaultTerm(terms, date = today()) {
  const running = terms.filter((term) => term.startDate <= date && date <= term.endDate);
  const byStart = [...terms].sort((a, b) => a.startDate.localeCompare(b.startDate));
  return running.find((term) => term.status === 'PUBLISHED') ?? running[0]
    ?? byStart.find((term) => term.startDate > date) ?? byStart.at(-1);
}

/** The term last chosen in this tab if this user can still see it, else the default. Undefined when there are none. */
export function initialTerm(terms) {
  return terms.find((term) => term.id === chosenTermId()) ?? defaultTerm(terms);
}

/**
 * The term select for the top bar, and the Draft or Published tag that goes with it. setTerms fills it, show(term)
 * makes it show that term, and choosing another calls onChange(term). A choice is remembered for the other screens,
 * unless remember is false, for a screen that remembers it only once the term has loaded.
 */
export function termPicker(onChange, { remember = true } = {}) {
  let terms = [];
  const select = el('select', {
    'aria-label': 'Term',
    onchange: () => {
      const term = terms.find((item) => item.id === select.value);
      if (!term) return;
      if (remember) rememberTerm(term.id);
      onChange(term);
    },
  });
  const tag = el('span', { class: 'tag' });

  return {
    select,
    tag,
    setTerms(list) {
      terms = list;
      select.replaceChildren(...sortNewestFirst(list).map((term) => el('option', { value: term.id }, termLabel(term))));
    },
    show(term) {
      const draft = term.status === 'DRAFT';
      select.value = term.id;
      tag.className = draft ? 'tag draft' : 'tag published';
      tag.textContent = draft ? 'Draft' : 'Published';
    },
  };
}
