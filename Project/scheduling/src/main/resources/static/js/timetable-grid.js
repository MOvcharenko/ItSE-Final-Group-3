// The Timetable Builder's week grid: one block per weekly meeting, dragged to a new day or time, and beside it a list
// of courses and sections that can be dragged onto the grid. Dragging is for a mouse or touch; a click or Enter on a
// block or list item opens the matching form, where the same change can be typed.
import { hourLabel, minutes, timeRange } from './dates.js';
import { el } from './dom.js';
import { openList } from './forms.js';
import { DAYS, plural, shortDay } from './labels.js';
import { fitColumns, layoutOverlaps } from './layout.js';

// More classes side by side than this and each is too thin to read: the rest go behind a "+N more" button.
const MAX_COLUMNS = 3;
const FIRST_HOUR = 8;
const LAST_HOUR = 18;
const HOUR_PX = 72; // keep in step with --hour in app.css
const SNAP_MINUTES = 5;
const DRAG_THRESHOLD_PX = 5;
const LIST_GRAB_PX = 10; // a list item is held near the top of its ghost block

const hhmm = (total) => `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
const overlaps = (a, b) => a.start < b.end && b.start < a.end;

/** Every weekly meeting as a grid item; one-time meetings and exams have a date, so they are not on a week grid. */
function weeklyItems(sections) {
  return sections.flatMap((section) => section.meetings.map((meeting, index) => ({ section, meeting, index }))
    .filter(({ meeting }) => meeting.pattern === 'WEEKLY')
    .map((item) => ({ ...item, day: item.meeting.day, start: minutes(item.meeting.start), end: minutes(item.meeting.end) })));
}

/** Times when the people or room of `section` are already in use by other sections, as [day, start, end, label]. */
function busyFor(section, items) {
  const people = new Set(section.instructors.map((person) => person.id));
  const busy = [];
  items.filter((item) => item.section.id !== section.id).forEach((item) => {
    const shared = item.section.instructors.filter((person) => people.has(person.id)).map((person) => person.name);
    if (shared.length) busy.push({ day: item.day, start: item.start, end: item.end, label: `${shared.join(', ')} teaching ${item.section.courseCode}` });
    if (section.roomId && item.section.roomId === section.roomId) {
      busy.push({ day: item.day, start: item.start, end: item.end, label: `${item.section.roomName} in use by ${item.section.courseCode}` });
    }
  });
  return busy;
}

/**
 * sections: all the term's sections. visible(section): whether its blocks are drawn (a filter); hidden sections still
 * count when a drag shades clashes, so a clash is never missed. conflictsOf(section, meeting): open conflicts that
 * meeting is in.
 * availabilityBlocks: one instructor's declared availability, shaded behind the blocks.
 * onOpen(section) opens the form; onMove(section, index, {day, start, end}) saves a drag.
 * palette: groups shown beside the grid, [{ heading, items: [{ label, detail, duration, section?, onClick, onDrop }] }].
 * An item dropped on the grid calls onDrop({day, start, end}); one with a section shades that section's busy times.
 * showWeekends adds Saturday and Sunday; without it a weekend day appears only when something already meets on it.
 */
export function weekGrid({ sections, visible = () => true, published, conflictsOf = () => 0, availabilityBlocks = [], onOpen, onMove, palette = [], showWeekends = false }) {
  const items = weeklyItems(sections);
  const shownItems = items.filter((item) => visible(item.section));
  const days = DAYS.filter(([value], index) => index < 5 || showWeekends || items.some((item) => item.day === value)).map(([value]) => value);
  const firstHour = Math.min(FIRST_HOUR, ...items.map((item) => Math.floor(item.start / 60)));
  const lastHour = Math.max(LAST_HOUR, ...items.map((item) => Math.ceil(item.end / 60)));
  const height = (lastHour - firstHour) * HOUR_PX;
  const toPx = (total) => (total - firstHour * 60) * HOUR_PX / 60;
  let suppressClick = false;

  const columns = new Map(days.map((day) => [day, el('div', { class: 'grid-col', style: `height:${height}px` })]));

  function band({ day, start, end, label }, kind) {
    columns.get(day)?.append(el('div', {
      class: `busy ${kind}`,
      style: `top:${toPx(start)}px;height:${Math.max(toPx(end) - toPx(start), 12)}px`,
      'aria-hidden': 'true',
    }, el('span', {}, label)));
  }

  // One instructor's declared availability (FR1.4 overlay lens); Open blocks need no shading
  availabilityBlocks.filter((block) => block.status !== 'OPEN').forEach((block) => {
    const unavailable = block.status === 'UNAVAILABLE';
    band({ day: block.dayOfWeek, start: minutes(block.startTime), end: minutes(block.endTime),
      label: unavailable ? 'Unavailable (declared)' : 'Preferred (declared)' }, unavailable ? 'overlay-unavailable' : 'overlay-prefer');
  });

  days.forEach((day) => {
    const placed = layoutOverlaps(shownItems.filter((item) => item.day === day)
      .map((item) => ({ ...item, start: hhmm(item.start), end: hhmm(item.end), item })));
    const { shown, more } = fitColumns(placed, MAX_COLUMNS);
    shown.forEach(({ event, column, columns: count }) => columns.get(day).append(block(event.item, column, count)));
    more.forEach((group) => columns.get(day).append(moreButton(day, group)));
  });

  /** Stands in for the classes a crowded time has no room for; opens a list of them. */
  function moreButton(day, group) {
    const width = 100 / group.columns;
    const when = timeRange(group.start, group.end);
    const label = `+${group.events.length}`; // a third of a day column: "more" is in the tooltip and the label
    return el('button', {
      class: 'block more',
      type: 'button',
      style: `top:${toPx(minutes(group.start))}px;height:${Math.max(toPx(minutes(group.end)) - toPx(minutes(group.start)), 24)}px;`
        + `left:calc(${group.column * width}% + 2px);width:calc(${width}% - 4px)`,
      'aria-label': `${group.events.length} more classes, ${shortDay(day)} ${when}. Show them`,
      title: `${group.events.length} more classes at this time. Filter by room or instructor to see them on the grid.`,
      onclick: () => openList(`${shortDay(day)} ${when}: ${plural(group.events.length, 'more class', 'more classes')}`,
        group.events.map(({ item }) => ({
          label: `${item.section.courseCode} · ${item.section.sectionNumber}`,
          detail: `${timeRange(item.meeting.start, item.meeting.end)}, ${item.section.roomName ?? 'no room'}`
            + (item.section.instructors.length ? `, ${item.section.instructors.map((person) => person.name).join(', ')}` : ''),
          onClick: () => onOpen(item.section),
        }))),
    }, el('span', { class: 'course' }, label));
  }

  function block(item, column, count) {
    const { section } = item;
    const width = 100 / count;
    const conflicts = conflictsOf(section, item.meeting);
    const when = timeRange(item.meeting.start, item.meeting.end);
    const classes = ['block', 'draggable', published ? 'published' : 'draft', conflicts && 'has-conflict', count > 1 && 'narrow'];
    const node = el('button', {
      class: classes.filter(Boolean).join(' '),
      type: 'button',
      style: `top:${toPx(item.start)}px;height:${Math.max(toPx(item.end) - toPx(item.start), 24)}px;left:calc(${column * width}% + 2px);width:calc(${width}% - 4px)`,
      'aria-label': `${section.courseCode} section ${section.sectionNumber}, ${shortDay(item.day)} ${when}, ${section.roomName ?? 'no room'}`
        + `${conflicts ? `, ${plural(conflicts, 'open conflict')}` : ''}. Edit section`,
      title: `${section.courseCode} · ${section.sectionNumber}, ${shortDay(item.day)} ${when}. Drag to move, or click to edit.`,
      onclick: () => {
        if (suppressClick) { suppressClick = false; return; }
        onOpen(section);
      },
      onpointerdown: (down) => startDrag(down, {
        node,
        label: `${section.courseCode} · ${section.sectionNumber}`,
        duration: item.end - item.start,
        grabOffset: down.clientY - node.getBoundingClientRect().top,
        busy: busyFor(section, items),
        onDrop: (spot) => {
          if (spot.day !== item.day || spot.start !== item.start) {
            onMove(section, item.index, { day: spot.day, start: hhmm(spot.start), end: hhmm(spot.end) });
          }
        },
      }),
    },
      el('span', { class: 'course' }, conflicts > 0 && el('span', { class: 'flag', 'aria-hidden': 'true' }, '!'),
        `${section.courseCode} · ${section.sectionNumber}`),
      el('span', {}, when),
      el('span', { class: 'room' }, section.roomName ?? 'No room'));
    return node;
  }

  /** A course or section in the list beside the grid: drag it onto a day and time, or click it to open its form. */
  function paletteItem(entry) {
    const node = el('button', {
      class: 'palette-item',
      type: 'button',
      title: `Drag onto the grid, or click to open the form`,
      onclick: () => {
        if (suppressClick) { suppressClick = false; return; }
        entry.onClick();
      },
      onpointerdown: (down) => startDrag(down, {
        node,
        label: entry.label,
        duration: entry.duration,
        grabOffset: LIST_GRAB_PX,
        // Its own meetings count too: a second meeting can't sit on top of the first.
        busy: entry.section ? [...busyFor(entry.section, items), ...ownMeetings(entry.section)] : [],
        onGrid: true,
        onDrop: (spot) => entry.onDrop({ day: spot.day, start: hhmm(spot.start), end: hhmm(spot.end) }),
      }),
    },
      el('span', { class: 'course' }, entry.label),
      entry.detail && el('span', { class: 'muted' }, entry.detail));
    return el('li', { 'data-search': `${entry.label} ${entry.detail ?? ''}`.toLowerCase() }, node);
  }

  function ownMeetings(section) {
    return items.filter((item) => item.section.id === section.id)
      .map((item) => ({ day: item.day, start: item.start, end: item.end, label: `${section.courseCode} · ${section.sectionNumber} already meets` }));
  }

  /**
   * Drags a ghost block over the grid. With onGrid, letting go outside the grid does nothing; otherwise the nearest
   * day is used, so a block dragged a little too far still lands. onDrop({day, start, end}) gets minutes since midnight.
   */
  function startDrag(down, { node, label, duration, grabOffset, busy, onGrid = false, onDrop }) {
    if (down.button !== 0 || !down.isPrimary) return;
    let ghost = null;
    let target = null;

    const where = (event) => {
      const rects = days.map((day) => [day, columns.get(day).getBoundingClientRect()]);
      const inside = rects.find(([, r]) => event.clientX >= r.left && event.clientX < r.right);
      if (onGrid && (!inside || event.clientY < inside[1].top || event.clientY > inside[1].bottom)) return null;
      const [day, rect] = inside ?? (event.clientX < rects[0][1].left ? rects[0] : rects.at(-1));
      const raw = firstHour * 60 + (event.clientY - grabOffset - rect.top) * 60 / HOUR_PX;
      const start = Math.min(Math.max(Math.round(raw / SNAP_MINUTES) * SNAP_MINUTES, 0), 24 * 60 - duration);
      return { day, start, end: start + duration };
    };

    const show = (spot) => {
      if (!spot) {
        ghost.remove();
        return;
      }
      const clash = busy.find((entry) => entry.day === spot.day && overlaps(entry, spot));
      ghost.className = `block ghost${clash ? ' clash' : ''}`;
      ghost.style.cssText = `top:${toPx(spot.start)}px;height:${toPx(spot.end) - toPx(spot.start)}px;left:2px;right:2px`;
      ghost.replaceChildren(el('span', { class: 'course' }, `${shortDay(spot.day)} ${timeRange(hhmm(spot.start), hhmm(spot.end))}`),
        el('span', {}, clash ? clash.label : label));
      columns.get(spot.day).append(ghost);
    };

    const finish = (save) => {
      node.removeEventListener('pointermove', onPointerMove);
      node.removeEventListener('pointerup', onPointerUp);
      node.removeEventListener('pointercancel', onPointerCancel);
      document.removeEventListener('keydown', onKey);
      if (!ghost) return;
      ghost.remove();
      grid.classList.remove('dragging');
      node.classList.remove('lifted');
      grid.querySelectorAll('.busy.drag').forEach((shade) => shade.remove());
      suppressClick = true; // the click that follows a drag is not a request to open the form
      if (save && target) onDrop(target);
    };
    const onPointerMove = (event) => {
      if (!ghost) {
        if (Math.hypot(event.clientX - down.clientX, event.clientY - down.clientY) < DRAG_THRESHOLD_PX) return;
        ghost = el('div', { 'aria-hidden': 'true' });
        grid.classList.add('dragging');
        node.classList.add('lifted');
        busy.forEach((entry) => band(entry, 'drag'));
      }
      target = where(event);
      show(target);
    };
    const onPointerUp = () => finish(true);
    const onPointerCancel = () => finish(false);
    const onKey = (event) => { if (event.key === 'Escape') finish(false); };

    node.setPointerCapture(down.pointerId);
    node.addEventListener('pointermove', onPointerMove);
    node.addEventListener('pointerup', onPointerUp);
    node.addEventListener('pointercancel', onPointerCancel);
    document.addEventListener('keydown', onKey);
  }

  const hourLabels = [];
  for (let hour = firstHour; hour < lastHour; hour++) {
    hourLabels.push(el('div', { class: 'grid-time', style: `top:${(hour - firstHour) * HOUR_PX}px` }, hourLabel(hour)));
  }
  const grid = el('div', { class: 'grid builder' },
    el('div', { class: 'grid-head' },
      el('div', { class: 'grid-corner' }),
      days.map((day) => el('div', { class: 'grid-day' }, shortDay(day)))),
    el('div', { class: 'grid-body' },
      el('div', { class: 'grid-times', style: `height:${height}px` }, hourLabels),
      days.map((day) => columns.get(day))));
  const gridBox = el('div', { class: 'scroll-x' }, grid);
  if (!palette.some((group) => group.items.length)) return gridBox;

  // Each group folds away, so a long list of courses doesn't push the sections out of reach.
  const groups = palette.filter((group) => group.items.length).map((group) => el('details', { class: 'palette-group', open: true },
    el('summary', {}, group.heading, el('span', { class: 'count' }, String(group.items.length))),
    el('ul', {}, group.items.map(paletteItem))));
  const none = el('p', { class: 'hint', hidden: true }, 'Nothing matches.');
  const find = el('input', {
    type: 'search',
    placeholder: 'Find a course or section',
    'aria-label': 'Find a course or section',
    oninput: () => {
      const text = find.value.trim().toLowerCase();
      let shown = 0;
      groups.forEach((group) => {
        let inGroup = 0;
        group.querySelectorAll('li').forEach((li) => {
          li.hidden = Boolean(text) && !li.dataset.search.includes(text);
          if (!li.hidden) inGroup++;
        });
        group.hidden = inGroup === 0;
        if (text && inGroup) group.open = true; // a match in a folded group is still found
        shown += inGroup;
      });
      none.hidden = shown > 0;
    },
  });
  return el('div', { class: 'builder-layout' },
    el('aside', { class: 'palette', 'aria-label': 'Courses and sections to place' }, find, groups, none),
    gridBox);
}

/** The meetings a week grid can't show: one-time meetings and exams, by date. */
export function datedMeetings(sections) {
  return sections.flatMap((section) => section.meetings.filter((meeting) => meeting.pattern !== 'WEEKLY')
    .map((meeting) => ({ section, meeting })))
    .sort((a, b) => a.meeting.date.localeCompare(b.meeting.date) || a.meeting.start.localeCompare(b.meeting.start));
}
