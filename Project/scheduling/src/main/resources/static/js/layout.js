// Placing events on a time grid, shared by the calendar and the Timetable week grid.
import { minutes } from './dates.js';

/** Gives events that overlap on one day their own columns, side by side. Events have start and end as "HH:mm". */
export function layoutOverlaps(events) {
  const sorted = [...events].sort((a, b) => minutes(a.start) - minutes(b.start) || minutes(a.end) - minutes(b.end));
  const placed = [];
  let cluster = [];
  let clusterEnd = -1;
  let clusterId = 0;
  const closeCluster = () => {
    const columnEnds = [];
    const start = placed.length;
    for (const event of cluster) {
      let column = columnEnds.findIndex((end) => end <= minutes(event.start));
      if (column < 0) column = columnEnds.length;
      columnEnds[column] = minutes(event.end);
      placed.push({ event, column, cluster: clusterId });
    }
    for (let i = start; i < placed.length; i++) placed[i].columns = columnEnds.length;
    cluster = [];
    clusterId++;
  };
  for (const event of sorted) {
    if (minutes(event.start) >= clusterEnd) {
      closeCluster();
      clusterEnd = -1;
    }
    cluster.push(event);
    clusterEnd = Math.max(clusterEnd, minutes(event.end));
  }
  closeCluster();
  return placed;
}

/**
 * Keeps a crowded time readable: where more than max events overlap, the first max - 1 columns stay and the rest
 * collapse into "more" groups in the last column, one per run of overlapping events. Returns { shown, more }, where
 * shown is placed entries (as from layoutOverlaps) and each group is { start, end, column, columns, events }.
 */
export function fitColumns(placed, max) {
  const shown = [];
  const extra = new Map(); // cluster → its events that don't fit
  placed.forEach((entry) => {
    if (entry.columns <= max) shown.push(entry);
    else if (entry.column < max - 1) shown.push({ ...entry, columns: max });
    else extra.set(entry.cluster, [...(extra.get(entry.cluster) ?? []), entry.event]);
  });
  const more = [];
  extra.forEach((events) => {
    let group = null;
    [...events].sort((a, b) => minutes(a.start) - minutes(b.start)).forEach((event) => {
      if (group && minutes(event.start) < minutes(group.end)) {
        group.events.push(event);
        if (minutes(event.end) > minutes(group.end)) group.end = event.end;
      } else {
        group = { start: event.start, end: event.end, column: max - 1, columns: max, events: [event] };
        more.push(group);
      }
    });
  });
  return { shown, more };
}
