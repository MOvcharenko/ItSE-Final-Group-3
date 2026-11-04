-- =====================================================================================
-- TD-1 (baseline), Phase 2 report TP3: Term Fall 2026 in Draft, seven sections, five rooms, four equipment types,
-- instructor availability, and KNOWN conflicts so expected results can be stated exactly.
-- Plain SQL against the V1 tables (no entities), so it works for every stream before their classes exist.
-- Applied by support.Td1Loader after reset(). Keep it free of semicolons and double dashes inside strings.
--
-- Known conflicts in this data (the conflict engine must find exactly these three):
--   1. ROOM_DOUBLE_BOOKING        CS 340 and ART 220, Innovation Lab B, Monday 13:00-14:15       (TC-FR4.3a)
--   2. INSTRUCTOR_UNAVAILABLE     CS 340 Monday 13:00-14:15 while Dr. Novak is unavailable 12:00-14:00 (TC-FR4.3c)
--   3. CAPACITY_EXCEEDED          ENG 101, Carter Center 301 seats 80, the SIS stub reports 92    (TC-FR4.3e)
-- MATH 210 and PHYS 110 overlap on Tuesday in different rooms with different instructors: a Student-group clash
-- appears only when the SIS stub shares 14 students between them (TC-FR4.3f).
-- Enrollment is NOT stored here (the SIS owns it): see support.Td1.ENROLLED for what the SIS stub must return.
-- =====================================================================================

-- ---------- people (TP3 accounts U01 and U02; Jordan Ellis U03 is a Student and is not stored) ----------
INSERT INTO app_user (id, display_name, email, department) VALUES ('U01', 'Maya Brooks',   'm.brooks@univ.edu',   NULL);
INSERT INTO app_user (id, display_name, email, department) VALUES ('U02', 'L. Bianchi',    'l.bianchi@univ.edu',  'MATH');
INSERT INTO app_user (id, display_name, email, department) VALUES ('U04', 'Dr. Novak',     'n.novak@univ.edu',    'CS');
INSERT INTO app_user (id, display_name, email, department) VALUES ('U05', 'Prof. Alvarez', 'm.alvarez@univ.edu',  'ENG');
INSERT INTO app_user (id, display_name, email, department) VALUES ('U06', 'Dr. Suzuki',    'k.suzuki@univ.edu',   'PHYS');
INSERT INTO app_user (id, display_name, email, department) VALUES ('U07', 'Dr. Patel',     'r.patel@univ.edu',    'CS');
INSERT INTO app_user (id, display_name, email, department) VALUES ('U08', 'Prof. Okafor',  'c.okafor@univ.edu',   'ART');
INSERT INTO app_user (id, display_name, email, department) VALUES ('U09', 'Dr. Haddad',    'y.haddad@univ.edu',   'PHYS');

-- Maya manages every department the test cases touch (the prototype lists CS and MATH; ART 220, ENG 101, HIST 120 and
-- PSY 220 appear in her test steps too, and TR3.4 would otherwise refuse them).
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'CS');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'MATH');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'ENG');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'BIO');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'PHYS');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'ART');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'HIST');
INSERT INTO scheduler_department (scheduler_id, department) VALUES ('U01', 'PSY');

-- ---------- the term ----------
INSERT INTO term (id, name, start_date, end_date, status) VALUES ('T-2026-FALL', 'Fall 2026', '2026-09-01', '2026-12-20', 'DRAFT');

-- ---------- rooms (TC-FR2.1) and their fixed equipment ----------
INSERT INTO room (id, name, building, capacity, room_type, status) VALUES ('R-HALE204', 'Hale Hall 204',     'Hale Hall',    120, 'LECTURE_HALL', 'AVAILABLE');
INSERT INTO room (id, name, building, capacity, room_type, status) VALUES ('R-LABB',    'Innovation Lab B',  'Innovation',    40, 'LAB',          'AVAILABLE');
INSERT INTO room (id, name, building, capacity, room_type, status) VALUES ('R-F110',    'Founders 110',      'Founders',      60, 'LECTURE_HALL', 'AVAILABLE');
INSERT INTO room (id, name, building, capacity, room_type, status) VALUES ('R-CC301',   'Carter Center 301', 'Carter Center', 80, 'LECTURE_HALL', 'AVAILABLE');
INSERT INTO room (id, name, building, capacity, room_type, status) VALUES ('R-SCI02',   'Sci Building 02',   'Sci Building',  30, 'LAB',          'AVAILABLE');

INSERT INTO room_fixed_equipment (room_id, item_type) VALUES ('R-HALE204', 'Projector');
INSERT INTO room_fixed_equipment (room_id, item_type) VALUES ('R-LABB',    'Projector');
INSERT INTO room_fixed_equipment (room_id, item_type) VALUES ('R-LABB',    'Whiteboard');
INSERT INTO room_fixed_equipment (room_id, item_type) VALUES ('R-F110',    'Projector');
INSERT INTO room_fixed_equipment (room_id, item_type) VALUES ('R-CC301',   'Projector');
INSERT INTO room_fixed_equipment (room_id, item_type) VALUES ('R-SCI02',   'Lab benches');

-- ---------- portable equipment: four types (TC-FR2.2). Two laptop carts make TC-FR4.3d and OI-1 work ----------
INSERT INTO equipment (id, name, item_type, unit_count, home_location, status) VALUES ('E-PROJ',   'Projector',       'Projector',       2, 'AV Storage, Hale Hall',     'AVAILABLE');
INSERT INTO equipment (id, name, item_type, unit_count, home_location, status) VALUES ('E-LAPTOP', 'Laptop cart',     'Laptop cart',     2, 'IT Services, Carter Center', 'AVAILABLE');
INSERT INTO equipment (id, name, item_type, unit_count, home_location, status) VALUES ('E-MIC',    'Mic kit',         'Mic kit',         3, 'AV Storage, Hale Hall',     'AVAILABLE');
INSERT INTO equipment (id, name, item_type, unit_count, home_location, status) VALUES ('E-DOCCAM', 'Document camera', 'Document camera', 1, 'Sci Building Office',      'AVAILABLE');

-- ---------- subjects ----------
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-CS201',   'CS 201',   'Introduction to Programming', 4, 'LAB',          'CS');
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-MATH210', 'MATH 210', 'Calculus II',                 4, 'LECTURE_HALL', 'MATH');
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-ENG101',  'ENG 101',  'Academic Writing',            3, 'LECTURE_HALL', 'ENG');
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-BIO150',  'BIO 150',  'Biostatistics',               3, 'LECTURE_HALL', 'BIO');
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-PHYS110', 'PHYS 110', 'Physics I Laboratory',        2, 'LAB',          'PHYS');
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-CS340',   'CS 340',   'Database Systems',            3, 'LAB',          'CS');
INSERT INTO subject (id, course_code, title, credit_hours, default_room_type, department) VALUES ('SUB-ART220',  'ART 220',  'Digital Design Studio',       3, 'SEMINAR_ROOM', 'ART');

INSERT INTO subject_equipment (subject_id, item_type) VALUES ('SUB-CS340',  'Laptop cart');
INSERT INTO subject_equipment (subject_id, item_type) VALUES ('SUB-ART220', 'Projector');

-- ---------- the seven sections of Fall 2026 (SEC-101 is MATH 210, as in the prototype) ----------
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-101', 'T-2026-FALL', 'SUB-MATH210', '01', 'R-HALE204');
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-102', 'T-2026-FALL', 'SUB-CS201',   '01', 'R-F110');
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-103', 'T-2026-FALL', 'SUB-ENG101',  '01', 'R-CC301');
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-104', 'T-2026-FALL', 'SUB-BIO150',  '01', 'R-HALE204');
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-105', 'T-2026-FALL', 'SUB-PHYS110', '01', 'R-SCI02');
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-106', 'T-2026-FALL', 'SUB-CS340',   '01', 'R-LABB');
INSERT INTO section (id, term_id, subject_id, section_number, room_id) VALUES ('SEC-107', 'T-2026-FALL', 'SUB-ART220',  '01', 'R-LABB');

INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-101', 'U02');
INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-102', 'U04');
INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-103', 'U05');
INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-104', 'U02');
INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-105', 'U09');
INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-106', 'U04');
INSERT INTO section_instructor (section_id, instructor_id) VALUES ('SEC-107', 'U08');

-- MATH 210: Tue and Thu 10:00-11:15 (2.5 h/week)
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-101-1', 'SEC-101', 'WEEKLY', 'TUESDAY',   '10:00', '11:15');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-101-2', 'SEC-101', 'WEEKLY', 'THURSDAY',  '10:00', '11:15');
-- CS 201: Wednesday 09:00-09:45 (0.75 h)
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-102-1', 'SEC-102', 'WEEKLY', 'WEDNESDAY', '09:00', '09:45');
-- ENG 101: Mon Wed Fri 09:00-11:00 and Tue Thu 09:00-10:30 (9 h)
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-103-1', 'SEC-103', 'WEEKLY', 'MONDAY',    '09:00', '11:00');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-103-2', 'SEC-103', 'WEEKLY', 'WEDNESDAY',  '09:00', '11:00');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-103-3', 'SEC-103', 'WEEKLY', 'FRIDAY',     '09:00', '11:00');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-103-4', 'SEC-103', 'WEEKLY', 'TUESDAY',    '09:00', '10:30');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-103-5', 'SEC-103', 'WEEKLY', 'THURSDAY',   '09:00', '10:30');
-- BIO 150: Mon Wed Fri 11:00-12:10 (3.5 h)
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-104-1', 'SEC-104', 'WEEKLY', 'MONDAY',    '11:00', '12:10');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-104-2', 'SEC-104', 'WEEKLY', 'WEDNESDAY',  '11:00', '12:10');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-104-3', 'SEC-104', 'WEEKLY', 'FRIDAY',     '11:00', '12:10');
-- PHYS 110 Lab: Tuesday 10:30-13:00 (2.5 h), overlaps MATH 210 on Tuesday (TC-FR4.3f)
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-105-1', 'SEC-105', 'WEEKLY', 'TUESDAY',    '10:30', '13:00');
-- CS 340 and ART 220: both Monday 13:00-14:15 in Innovation Lab B (the room double-booking)
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-106-1', 'SEC-106', 'WEEKLY', 'MONDAY',    '13:00', '14:15');
INSERT INTO section_meeting (id, section_id, pattern, day_of_week, start_time, end_time) VALUES ('MT-107-1', 'SEC-107', 'WEEKLY', 'MONDAY',    '13:00', '14:15');

-- ---------- instructor availability (Open + Prefer hours are the denominators of TC-FR5.1) ----------
-- Dr. Novak: 8 h open or preferred, Unavailable Monday 12:00-14:00 (TC-FR1.4, TC-FR4.3c). Scheduled 2 h -> 2 of 8.
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-N1', 'T-2026-FALL', 'U04', 'MONDAY',    '08:00', '12:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-N2', 'T-2026-FALL', 'U04', 'MONDAY',    '12:00', '14:00', 'UNAVAILABLE');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-N3', 'T-2026-FALL', 'U04', 'WEDNESDAY', '09:00', '12:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-N4', 'T-2026-FALL', 'U04', 'FRIDAY',    '10:00', '11:00', 'PREFER');
-- L. Bianchi: 18 h open or preferred. Scheduled 6 h -> 6 of 18.
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-B1', 'T-2026-FALL', 'U02', 'MONDAY',    '09:00', '13:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-B2', 'T-2026-FALL', 'U02', 'TUESDAY',   '09:00', '13:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-B3', 'T-2026-FALL', 'U02', 'WEDNESDAY', '09:00', '13:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-B4', 'T-2026-FALL', 'U02', 'THURSDAY',  '09:00', '13:00', 'PREFER');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-B5', 'T-2026-FALL', 'U02', 'FRIDAY',    '09:00', '11:00', 'OPEN');
-- Prof. Alvarez: 10 h open or preferred. Scheduled 9 h -> 9 of 10 (positive confirmation).
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-A1', 'T-2026-FALL', 'U05', 'MONDAY',    '09:00', '11:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-A2', 'T-2026-FALL', 'U05', 'TUESDAY',   '09:00', '10:30', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-A3', 'T-2026-FALL', 'U05', 'WEDNESDAY', '09:00', '11:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-A4', 'T-2026-FALL', 'U05', 'THURSDAY',  '09:00', '10:30', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-A5', 'T-2026-FALL', 'U05', 'FRIDAY',    '09:00', '11:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-A6', 'T-2026-FALL', 'U05', 'FRIDAY',    '13:00', '14:00', 'PREFER');
-- Mid-range loads (no suggestion, no confirmation): Dr. Haddad 2.5 of 4 h, Prof. Okafor 1.25 of 2 h.
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-H1', 'T-2026-FALL', 'U09', 'TUESDAY',   '10:00', '14:00', 'OPEN');
INSERT INTO instructor_availability (id, term_id, instructor_id, day_of_week, start_time, end_time, status) VALUES ('AV-O1', 'T-2026-FALL', 'U08', 'MONDAY',    '13:00', '15:00', 'OPEN');
-- Dr. Suzuki and Dr. Patel declare nothing and teach nothing yet (TC-FR1.6b adds Suzuki, the TD-2 imports use Patel).

-- ---------- booking history (TC-FR2.5: past bookings stay visible when a resource goes into Maintenance) ----------
INSERT INTO booking (id, requester_id, resource_type, room_id,      equipment_id, start_time, end_time, purpose, status, created_at, decided_by, decided_at)
VALUES ('B-HIST-1', 'U02', 'ROOM',      'R-SCI02', NULL,       '2026-10-05 13:00:00', '2026-10-05 15:00:00', 'Lab demonstration',  'APPROVED', '2026-09-28 09:00:00', 'U01', '2026-09-29 10:00:00');
INSERT INTO booking (id, requester_id, resource_type, room_id,      equipment_id, start_time, end_time, purpose, status, created_at, decided_by, decided_at)
VALUES ('B-HIST-2', 'U09', 'EQUIPMENT', NULL,      'E-DOCCAM', '2026-10-06 09:00:00', '2026-10-06 11:00:00', 'Lecture recording',  'APPROVED', '2026-09-30 11:00:00', 'U01', '2026-10-01 08:30:00');
