// The Timetable Builder's week grid: one block per weekly meeting, dragged to a new day or time. Dragging is for a
// mouse or touch; a click or Enter on a block opens the section's form, where the same change can be typed.
import { layoutOverlaps } from './calendar.js';
import { hourLabel, minutes, timeRange } from './dates.js';
import { el } from './dom.js';

const DAYS = [['MONDAY', 'Mon'], ['TUESDAY', 'Tue'], ['WEDNESDAY', 'Wed'], ['THURSDAY', 'Thu'], ['FRIDAY', 'Fri'],
  ['SATURDAY', 'Sat'], ['SUNDAY', 'Sun']];
const FIRST_HOUR = 8;
const LAST_HOUR = 18;
const HOUR_PX = 72; // keep in step with --hour in app.css
const SNAP_MINUTES = 5;
const DRAG_THRESHOLD_PX = 5;

const hhmm = (total) => `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
const dayName = (day) => DAYS.find(([value]) => value === day)[1];
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
 * sections: the term's sections. conflictCounts: open conflicts per section id. busyInstructor: an instructor id whose
 * teaching is shaded, or ''. onOpen(section) opens the form; onMove(section, index, {day, start, end}) saves a drag.
 */
export function weekGrid({ sections, published, conflictCounts, busyInstructor, onOpen, onMove }) {
  const items = weeklyItems(sections);
  const days = DAYS.filter(([value], index) => index < 5 || items.some((item) => item.day === value)).map(([value]) => value);
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

  // The chosen instructor's teaching, shaded behind the blocks; their own blocks stay bright and the rest dim
  const chosen = busyInstructor ? items.filter((item) => item.section.instructors.some((person) => person.id === busyInstructor)) : [];
  chosen.forEach((item) => band({ ...item, label: `Busy: ${item.section.courseCode}` }, 'overlay'));

  days.forEach((day) => {
    layoutOverlaps(items.filter((item) => item.day === day).map((item) => ({ ...item, start: hhmm(item.start), end: hhmm(item.end), item })))
      .forEach(({ event, column, columns: count }) => columns.get(day).append(block(event.item, column, count)));
  });

  function block(item, column, count) {
    const { section } = item;
    const width = 100 / count;
    const conflicts = conflictCounts.get(section.id) ?? 0;
    const when = timeRange(item.meeting.start, item.meeting.end);
    const dim = busyInstructor && !section.instructors.some((person) => person.id === busyInstructor);
    const classes = ['block', 'draggable', published ? 'published' : 'draft', conflicts && 'has-conflict', count > 1 && 'narrow', dim && 'dim'];
    const node = el('button', {
      class: classes.filter(Boolean).join(' '),
      type: 'button',
      style: `top:${toPx(item.start)}px;height:${Math.max(toPx(item.end) - toPx(item.start), 24)}px;left:calc(${column * width}% + 2px);width:calc(${width}% - 4px)`,
      'aria-label': `${section.courseCode} section ${section.sectionNumber}, ${dayName(item.day)} ${when}, ${section.roomName ?? 'no room'}`
        + `${conflicts ? `, ${conflicts === 1 ? '1 open conflict' : `${conflicts} open conflicts`}` : ''}. Edit section`,
      title: `${section.courseCode} · ${section.sectionNumber}, ${dayName(item.day)} ${when}. Drag to move, or click to edit.`,
      onclick: () => {
        if (suppressClick) { suppressClick = false; return; }
        onOpen(section);
      },
      onpointerdown: (event) => startDrag(event, item, node),
    },
      el('span', { class: 'course' }, conflicts > 0 && el('span', { class: 'flag', 'aria-hidden': 'true' }, '!'),
        `${section.courseCode} · ${section.sectionNumber}`),
      el('span', {}, when),
      el('span', { class: 'room' }, section.roomName ?? 'No room'));
    return node;
  }

  function startDrag(down, item, node) {
    if (down.button !== 0 || !down.isPrimary) return;
    const duration = item.end - item.start;
    const grabOffset = down.clientY - node.getBoundingClientRect().top;
    const busy = busyFor(item.section, items);
    let ghost = null;
    let target = null;

    const where = (event) => {
      const rects = days.map((day) => [day, columns.get(day).getBoundingClientRect()]);
      const [day, rect] = rects.find(([, r]) => event.clientX >= r.left && event.clientX < r.right)
        ?? (event.clientX < rects[0][1].left ? rects[0] : rects.at(-1));
      const raw = firstHour * 60 + (event.clientY - grabOffset - rect.top) * 60 / HOUR_PX;
      const start = Math.min(Math.max(Math.round(raw / SNAP_MINUTES) * SNAP_MINUTES, 0), 24 * 60 - duration);
      return { day, start, end: start + duration };
    };

    const show = (spot) => {
      const clash = busy.find((entry) => entry.day === spot.day && overlaps(entry, spot));
      ghost.className = `block ghost${clash ? ' clash' : ''}`;
      ghost.style.cssText = `top:${toPx(spot.start)}px;height:${toPx(spot.end) - toPx(spot.start)}px;left:2px;right:2px`;
      ghost.replaceChildren(el('span', { class: 'course' }, `${dayName(spot.day)} ${timeRange(hhmm(spot.start), hhmm(spot.end))}`),
        el('span', {}, clash ? clash.label : `${item.section.courseCode} · ${item.section.sectionNumber}`));
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
      if (save && target && (target.day !== item.day || target.start !== item.start)) {
        onMove(item.section, item.index, { day: target.day, start: hhmm(target.start), end: hhmm(target.end) });
      }
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
      days.map((day) => el('div', { class: 'grid-day' }, dayName(day)))),
    el('div', { class: 'grid-body' },
      el('div', { class: 'grid-times', style: `height:${height}px` }, hourLabels),
      days.map((day) => columns.get(day))));
  return el('div', { class: 'scroll-x' }, grid);
}

/** The meetings a week grid can't show: one-time meetings and exams, by date. */
export function datedMeetings(sections) {
  return sections.flatMap((section) => section.meetings.filter((meeting) => meeting.pattern !== 'WEEKLY')
    .map((meeting) => ({ section, meeting })))
    .sort((a, b) => a.meeting.date.localeCompare(b.meeting.date) || a.meeting.start.localeCompare(b.meeting.start));
}
