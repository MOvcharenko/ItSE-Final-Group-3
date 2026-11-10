// Placing events on a time grid, shared by the calendar and the Timetable week grid.
import { minutes } from './dates.js';

/** Gives events that overlap on one day their own columns, side by side. Events have start and end as "HH:mm". */
export function layoutOverlaps(events) {
  const sorted = [...events].sort((a, b) => minutes(a.start) - minutes(b.start) || minutes(a.end) - minutes(b.end));
  const placed = [];
  let cluster = [];
  let clusterEnd = -1;
  const closeCluster = () => {
    const columnEnds = [];
    const start = placed.length;
    for (const event of cluster) {
      let column = columnEnds.findIndex((end) => end <= minutes(event.start));
      if (column < 0) column = columnEnds.length;
      columnEnds[column] = minutes(event.end);
      placed.push({ event, column });
    }
    for (let i = start; i < placed.length; i++) placed[i].columns = columnEnds.length;
    cluster = [];
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
