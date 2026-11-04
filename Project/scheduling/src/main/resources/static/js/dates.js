// Calendar dates and times are local with no time zone, so everything here works on "YYYY-MM-DD" and "HH:mm" text.
// toISOString is avoided because it converts to UTC and can land on the wrong day.

const pad = (n) => String(n).padStart(2, '0');

function parse(iso) {
  const [year, month, day] = iso.split('-').map(Number);
  return new Date(year, month - 1, day);
}

function toIso(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function today() {
  return toIso(new Date());
}

export function addDays(iso, days) {
  const date = parse(iso);
  date.setDate(date.getDate() + days);
  return toIso(date);
}

function format(iso, options) {
  return parse(iso).toLocaleDateString('en-US', options);
}

/** "Mon 9" */
export function dayHeading(iso) {
  return format(iso, { weekday: 'short', day: 'numeric' }).split(' ').reverse().join(' ');
}

/** "Wed, Nov 4, 2026" */
export function longDate(iso) {
  return format(iso, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
}

/** "Wed, Nov 4" */
export function shortDate(iso) {
  return format(iso, { weekday: 'short', month: 'short', day: 'numeric' });
}

/** "Nov 2 – 8, 2026", "Oct 26 – Nov 1, 2026" or "Dec 28, 2026 – Jan 3, 2027" */
export function dateRange(from, to) {
  const start = parse(from);
  const end = parse(to);
  const endText = format(to, { month: 'short', day: 'numeric', year: 'numeric' });
  if (start.getFullYear() !== end.getFullYear()) {
    return `${format(from, { month: 'short', day: 'numeric', year: 'numeric' })} – ${endText}`;
  }
  if (start.getMonth() !== end.getMonth()) {
    return `${format(from, { month: 'short', day: 'numeric' })} – ${endText}`;
  }
  return `${format(from, { month: 'short', day: 'numeric' })} – ${end.getDate()}, ${end.getFullYear()}`;
}

/** "Nov 4, 2:10 pm" from a local date-time such as "2026-11-04T14:10:00". */
export function dateTime(value) {
  const [date, time] = value.split('T');
  return `${format(date, { month: 'short', day: 'numeric' })}, ${clock(time)} ${meridiem(time)}`;
}

/** "Tue, Nov 10, 5:00–6:30 pm", or both dates when a window spans days. Takes local date-times start and end. */
export function whenLabel({ start, end }) {
  const [startDate, startTime] = start.split('T');
  const [endDate, endTime] = end.split('T');
  return startDate === endDate
    ? `${shortDate(startDate)}, ${timeRange(startTime, endTime)}`
    : `${dateTime(start)} – ${dateTime(end)}`;
}

/** Minutes since midnight. */
export function minutes(time) {
  const [hour, minute] = time.split(':').map(Number);
  return hour * 60 + minute;
}

function clock(time) {
  const [hour, minute] = time.split(':').map(Number);
  return `${hour % 12 || 12}:${pad(minute)}`;
}

function meridiem(time) {
  return minutes(time) < 720 ? 'am' : 'pm';
}

/** "9:00–9:50 am" or "11:00 am–12:15 pm" */
export function timeRange(start, end) {
  const startSuffix = meridiem(start) === meridiem(end) ? '' : ` ${meridiem(start)}`;
  return `${clock(start)}${startSuffix}–${clock(end)} ${meridiem(end)}`;
}

/** "9 am", for the grid's hour labels. */
export function hourLabel(hour) {
  return `${hour % 12 || 12} ${hour < 12 ? 'am' : 'pm'}`;
}
