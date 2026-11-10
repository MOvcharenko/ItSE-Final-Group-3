// Every call the screens make to the Academic Scheduling API, named for what it does. Only this file knows the URLs.
// The HTTP functions are passed in, so a test can construct SchedulingApi with fakes and no server.
import * as http from './http.js';

const id = encodeURIComponent;

/** URLSearchParams without the blank values, so a form can pass its unset fields. */
function query(params) {
  return new URLSearchParams(Object.entries(params).filter(([, value]) => value != null && value !== ''));
}

export class SchedulingApi {
  constructor({ getJson, post, put, patch, del, upload, download }) {
    this.http = { getJson, post, put, patch, del, upload, download };
  }

  // --- terms ---------------------------------------------------------------
  terms() { return this.http.getJson('/api/terms'); }
  createTerm(term) { return this.http.post('/api/terms', term); }
  /** changes: only the fields to change; the API saves them all or none. */
  updateTerm(termId, changes) { return this.http.patch(`/api/terms/${id(termId)}`, changes); }
  copyTerm(termId, name) { return this.http.post(`/api/terms/${id(termId)}/copy`, { name }); }
  deleteTerm(termId) { return this.http.del(`/api/terms/${id(termId)}`); }
  /** Open High-severity conflicts answer 409 until confirmed. */
  publishTerm(termId, confirmed = false) {
    return this.http.post(`/api/terms/${id(termId)}/publish${confirmed ? '?confirm=true' : ''}`);
  }

  // --- sections ------------------------------------------------------------
  sections(termId) { return this.http.getJson(`/api/terms/${id(termId)}/sections`); }
  addSection(termId, section) { return this.http.post(`/api/terms/${id(termId)}/sections`, section); }
  saveSection(termId, sectionId, section) {
    return this.http.put(`/api/terms/${id(termId)}/sections/${id(sectionId)}`, section);
  }
  removeSection(termId, sectionId) { return this.http.del(`/api/terms/${id(termId)}/sections/${id(sectionId)}`); }
  /** assignment: { roomId, instructorIds }, each optional. */
  reassignSection(sectionId, assignment) { return this.http.patch(`/api/sections/${id(sectionId)}/assignment`, assignment); }

  // --- conflicts and insights ----------------------------------------------
  /** status: OPEN, RESOLVED or DISMISSED; all of them when left out. */
  conflicts(termId, status) { return this.http.getJson(`/api/terms/${id(termId)}/conflicts${status ? `?${query({ status })}` : ''}`); }
  dismissals(termId) { return this.http.getJson(`/api/terms/${id(termId)}/dismissals`); }
  rescan(termId) { return this.http.post(`/api/terms/${id(termId)}/rescan`); }
  dismissConflict(conflictId, reason) { return this.http.post(`/api/conflicts/${id(conflictId)}/dismiss`, { reason }); }
  insights(termId) { return this.http.getJson(`/api/terms/${id(termId)}/insights`); }
  dismissInsight(insightId) { return this.http.post(`/api/insights/${id(insightId)}/dismiss`); }

  // --- catalog -------------------------------------------------------------
  subjects() { return this.http.getJson('/api/subjects'); }
  addSubject(subject) { return this.http.post('/api/subjects', subject); }
  rooms() { return this.http.getJson('/api/rooms'); }
  addRoom(room) { return this.http.post('/api/rooms', room); }
  setRoomStatus(roomId, status) { return this.http.patch(`/api/rooms/${id(roomId)}/status`, { status }); }
  equipment() { return this.http.getJson('/api/equipment'); }
  addEquipment(item) { return this.http.post('/api/equipment', item); }
  setEquipmentStatus(itemId, status) { return this.http.patch(`/api/equipment/${id(itemId)}/status`, { status }); }
  instructors() { return this.http.getJson('/api/instructors'); }

  // --- import --------------------------------------------------------------
  /** type: ready-made-schedule, subject-list, rooms or equipment. */
  downloadImportTemplate(type) { return this.http.download(`/api/import/templates/${id(type)}`, `${type}-template.csv`); }
  importFile(type, file) { return this.http.upload(`/api/import/${id(type)}`, file); }

  // --- bookings and availability search ------------------------------------
  bookings(status) { return this.http.getJson(status ? `/api/bookings?${query({ status })}` : '/api/bookings'); }
  /** request: { resourceType, resourceId, start, end, purpose }. */
  requestBooking(request) { return this.http.post('/api/bookings', request); }
  approveBooking(bookingId) { return this.http.post(`/api/bookings/${id(bookingId)}/approve`); }
  cancelBooking(bookingId, note) { return this.http.post(`/api/bookings/${id(bookingId)}/cancel`, { note }); }
  /** kind: ROOM or EQUIPMENT. filters: start, end, and minCapacity, roomType, equipment or itemType. */
  searchFree(kind, filters) {
    return this.http.getJson(`/api/search/${kind === 'ROOM' ? 'rooms' : 'equipment'}?${query(filters)}`);
  }

  // --- calendar and notices ------------------------------------------------
  /** filters: termId, view, date, course, instructor, building, room. */
  calendar(filters) { return this.http.getJson(`/api/calendar?${query(filters)}`); }
  downloadCalendar(filters, fileName) { return this.http.download(`/api/calendar.ics?${query(filters)}`, fileName); }
  notices() { return this.http.getJson('/api/notices'); }
  markNoticeRead(noticeId) { return this.http.post(`/api/notices/${id(noticeId)}/read`); }

  // --- an instructor's teaching and availability ---------------------------
  teaching(termId) { return this.http.getJson(`/api/teaching?${query({ termId })}`); }
  myAvailability(termId) { return this.http.getJson(`/api/terms/${id(termId)}/availability/me`); }
  /** declaration: { blocks, equipmentNeeds, notes }. */
  saveMyAvailability(termId, declaration) { return this.http.put(`/api/terms/${id(termId)}/availability/me`, declaration); }
  instructorAvailability(instructorId, termId) {
    return this.http.getJson(`/api/instructors/${id(instructorId)}/availability?${query({ termId })}`);
  }
}

export const api = new SchedulingApi(http);
