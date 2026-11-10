// How the domain is worded on screen, shared by every screen so a wording changes in one place.
import { shortDate, timeRange } from './dates.js';

/** "1 section", "3 sections"; many defaults to one + "s". */
export const plural = (count, one, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

/** The label paired with value in a list of [value, label], or the value itself when it isn't listed. */
export const labelOf = (pairs, value) => pairs.find(([key]) => key === value)?.[1] ?? value;

export const DAYS = [['MONDAY', 'Monday'], ['TUESDAY', 'Tuesday'], ['WEDNESDAY', 'Wednesday'], ['THURSDAY', 'Thursday'],
  ['FRIDAY', 'Friday'], ['SATURDAY', 'Saturday'], ['SUNDAY', 'Sunday']];

/** "Mon" */
export const shortDay = (day) => labelOf(DAYS, day).slice(0, 3);

export const ROOM_TYPES = [['LECTURE_HALL', 'Lecture hall'], ['LAB', 'Lab'], ['SEMINAR_ROOM', 'Seminar room']];

export const roomTypeLabel = (type) => labelOf(ROOM_TYPES, type);

/** "Mon 10:00–10:50 am", or "Exam, Tue, Dec 15, 9:00–11:00 am" for a dated meeting. */
export function meetingLabel(meeting) {
  const when = timeRange(meeting.start, meeting.end);
  if (meeting.pattern === 'WEEKLY') return `${shortDay(meeting.day)} ${when}`;
  return `${meeting.pattern === 'EXAM' ? 'Exam, ' : ''}${shortDate(meeting.date)}, ${when}`;
}
