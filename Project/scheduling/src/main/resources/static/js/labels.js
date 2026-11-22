// How the domain is worded on screen, shared by every screen so a wording changes in one place.
import { shortDate, timeRange } from './dates.js';

/** "1 section", "3 sections"; many defaults to one + "s". */
export const plural = (count, one, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

/** The label paired with value in a list of [value, label], or the value itself when it isn't listed. */
export const labelOf = (pairs, value) => pairs.find(([key]) => key === value)?.[1] ?? value;

export const DAYS = [['MONDAY', 'Monday'], ['TUESDAY', 'Tuesday'], ['WEDNESDAY', 'Wednesday'], ['THURSDAY', 'Thursday'],
  ['FRIDAY', 'Friday'], ['SATURDAY', 'Saturday'], ['SUNDAY', 'Sunday']];

/** Saturday and Sunday: shown on the week grid only on request, and a class put there is confirmed first. */
export const isWeekend = (day) => day === 'SATURDAY' || day === 'SUNDAY';

/** "Mon" */
export const shortDay = (day) => labelOf(DAYS, day).slice(0, 3);

export const ROOM_TYPES = [['LECTURE_HALL', 'Lecture hall'], ['LAB', 'Lab'], ['SEMINAR_ROOM', 'Seminar room']];

export const roomTypeLabel = (type) => labelOf(ROOM_TYPES, type);

/**
 * A section's meetings in as few lines as can say them: weekly meetings at the same time share one line,
 * "Mon, Wed, Fri 9:00–9:50 am", in week order; dated meetings follow, one line each.
 */
export function meetingLines(meetings) {
  const weekly = new Map();
  meetings.filter((meeting) => meeting.pattern === 'WEEKLY').forEach((meeting) => {
    const key = `${meeting.start}-${meeting.end}`;
    weekly.set(key, [...(weekly.get(key) ?? []), meeting]);
  });
  const dayIndex = (day) => DAYS.findIndex(([value]) => value === day);
  const lines = [...weekly.values()]
    .map((group) => group.sort((a, b) => dayIndex(a.day) - dayIndex(b.day)))
    .sort((a, b) => dayIndex(a[0].day) - dayIndex(b[0].day) || a[0].start.localeCompare(b[0].start))
    .map((group) => `${group.map((meeting) => shortDay(meeting.day)).join(', ')} ${timeRange(group[0].start, group[0].end)}`);
  return [...lines, ...meetings.filter((meeting) => meeting.pattern !== 'WEEKLY').map(meetingLabel)];
}

/** "Mon 10:00–10:50 am", or "Exam, Tue, Dec 15, 9:00–11:00 am" for a dated meeting. */
export function meetingLabel(meeting) {
  const when = timeRange(meeting.start, meeting.end);
  if (meeting.pattern === 'WEEKLY') return `${shortDay(meeting.day)} ${when}`;
  return `${meeting.pattern === 'EXAM' ? 'Exam, ' : ''}${shortDate(meeting.date)}, ${when}`;
}
