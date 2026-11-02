package schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JDBC check of V1__schema.sql: runs the migration, then proves the constraints the spec relies on.
 * Usage: java SchemaCheck <jdbc-url> <user> <password> <path-to-V1__schema.sql>
 * Throw-away harness (no Spring, no Maven). The same cases belong in a Spring @DataJpaTest/@JdbcTest later.
 */
public class SchemaCheck {

    static int passed = 0, failed = 0;
    static Connection c;

    public static void main(String[] a) throws Exception {
        c = DriverManager.getConnection(a[0], a[1], a[2]);
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS public CASCADE");   // H2 and PostgreSQL both accept this
            s.execute("CREATE SCHEMA public");
        } catch (SQLException e) { /* fresh in-memory DB */ }

        // 1. the migration itself
        String sql = Files.readString(Path.of(a[3]));
        StringBuilder noComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            int i = line.indexOf("--");
            noComments.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        int statements = 0;
        try (Statement s = c.createStatement()) {
            for (String stmt : noComments.toString().split(";")) {
                if (stmt.isBlank()) continue;
                s.execute(stmt);
                statements++;
            }
        }
        System.out.println("migration applied: " + statements + " statements");

        // 2. valid data: a slice of TD-1 plus every table
        ok("users", "INSERT INTO app_user (id, display_name, email, department) VALUES " +
                "('U01','Maya Brooks','m.brooks@univ.edu',NULL), ('U02','L. Bianchi','l.bianchi@univ.edu','MATH'), " +
                "('U04','Dr. Novak','n@univ.edu','CS')");
        ok("scheduler departments", "INSERT INTO scheduler_department VALUES ('U01','CS'), ('U01','MATH')");
        ok("term", "INSERT INTO term (id,name,start_date,end_date,status) VALUES ('T1','Fall 2026','2026-09-01','2026-12-20','DRAFT')");
        ok("subject without department (User's Guide)", "INSERT INTO subject (id,course_code,title,credit_hours,default_room_type) VALUES ('SUB-PSY','PSY 220','Cognitive Psychology',3,'LECTURE_HALL')");
        ok("subject", "INSERT INTO subject (id,course_code,title,credit_hours,default_room_type,department) VALUES ('SUB-CS340','CS 340','Databases',3,'LAB','CS')");
        ok("subject equipment", "INSERT INTO subject_equipment VALUES ('SUB-CS340','Projector')");
        ok("rooms", "INSERT INTO room (id,name,building,capacity,room_type) VALUES ('R-LABB','Innovation Lab B','Innovation',40,'LAB'), ('R-F110','Founders 110','Founders',60,'LECTURE_HALL')");
        ok("room fixed equipment", "INSERT INTO room_fixed_equipment VALUES ('R-LABB','Projector')");
        ok("equipment", "INSERT INTO equipment (id,name,item_type,unit_count,home_location) VALUES ('E-SPK','Portable speaker','Speaker',3,'AV Storage, Hale Hall')");
        ok("section", "INSERT INTO section (id,term_id,subject_id,room_id) VALUES ('S1','T1','SUB-CS340','R-LABB')");
        ok("unplaced section (no room yet)", "INSERT INTO section (id,term_id,subject_id,section_number) VALUES ('S9','T1','SUB-PSY','01')");
        ok("MWF = three WEEKLY meetings", "INSERT INTO section_meeting (id,section_id,pattern,day_of_week,start_time,end_time) VALUES " +
                "('M1','S1','WEEKLY','MONDAY','13:00','13:50'), ('M2','S1','WEEKLY','WEDNESDAY','13:00','13:50'), ('M3','S1','WEEKLY','FRIDAY','13:00','13:50')");
        ok("EXAM meeting with a date", "INSERT INTO section_meeting (id,section_id,pattern,meeting_date,start_time,end_time) VALUES ('M4','S1','EXAM','2026-12-10','09:00','11:00')");
        ok("co-instructors (FR1.6)", "INSERT INTO section_instructor VALUES ('S1','U02'), ('S1','U04')");
        ok("section equipment", "INSERT INTO section_equipment (section_id,equipment_id) VALUES ('S1','E-SPK')");
        ok("room booking", "INSERT INTO booking (id,requester_id,resource_type,room_id,start_time,end_time,purpose,created_at) VALUES ('B1','U02','ROOM','R-F110','2026-10-14 17:00:00','2026-10-14 18:30:00','Guest talk','2026-10-01 09:00:00')");
        ok("equipment booking in the same table", "INSERT INTO booking (id,requester_id,resource_type,equipment_id,start_time,end_time,purpose,created_at) VALUES ('B2','U02','EQUIPMENT','E-SPK','2026-10-14 17:00:00','2026-10-14 18:30:00','Guest talk','2026-10-01 09:00:00')");
        ok("availability Unavailable Mon 12-14", "INSERT INTO instructor_availability (id,term_id,instructor_id,day_of_week,start_time,end_time,status) VALUES ('A1','T1','U04','MONDAY','12:00','14:00','UNAVAILABLE')");
        ok("availability note + equipment need", "INSERT INTO availability_submission VALUES ('T1','U04','Prefer mornings','2026-08-01 10:00:00')");
        ok("equipment need", "INSERT INTO instructor_equipment_need VALUES ('T1','U04','projector')");
        ok("conflict with typed references", "INSERT INTO conflict (id,term_id,conflict_type,severity,explanation,fingerprint,detected_at,instructor_id) VALUES ('C1','T1','INSTRUCTOR_UNAVAILABLE','HIGH','CS 340 is scheduled while Dr. Novak is unavailable','INSTRUCTOR_UNAVAILABLE:S1:U04','2026-10-24 12:00:00','U04')");
        ok("conflict_section", "INSERT INTO conflict_section VALUES ('C1','S1')");
        ok("audit entry", "INSERT INTO audit_log_entry VALUES ('AU1','C1','INSTRUCTOR_UNAVAILABLE','CS 340 is scheduled while Dr. Novak is unavailable','U01','2026-10-24 12:05:00','Covered by a guest lecturer')");
        ok("instructor insight", "INSERT INTO insight_suggestion (id,term_id,axis,instructor_id,related_conflict_id,message,created_at) VALUES ('I1','T1','INSTRUCTOR_UTILIZATION','U04','C1','Dr. Novak is scheduled 2 of 8 open hours','2026-10-24 12:00:00')");
        ok("room insight with measure", "INSERT INTO insight_suggestion (id,term_id,axis,measure,room_id,message,created_at) VALUES ('I2','T1','ROOM_CAPACITY','FILL','R-F110','Founders 110 runs 30% full','2026-10-24 12:00:00')");
        ok("import batch", "INSERT INTO import_batch VALUES ('IB1','faulty.csv','SUBJECT_LIST','2026-10-24 12:10:00','U01')");
        ok("import rows", "INSERT INTO import_row VALUES ('IB1',1,'ADDED',NULL), ('IB1',2,'NEEDS_REVIEW','duplicate subject code')");
        ok("notice for a student not in app_user", "INSERT INTO notice (id,recipient_id,term_id,section_id,trigger_event,message,created_at) VALUES ('N1','U03','T1','S1','ROOM_CHANGED','CS 340 moved to Founders 110','2026-10-24 12:00:00')");

        // 3. things the spec says must be refused
        bad("duplicate course code (UC-12 6a)", "INSERT INTO subject (id,course_code,title,credit_hours,default_room_type) VALUES ('X','CS 340','Dup',3,'LAB')");
        bad("duplicate room name", "INSERT INTO room (id,name,building,capacity,room_type) VALUES ('X','Founders 110','F',10,'LAB')");
        bad("room capacity -5 (TC-FR3.2)", "INSERT INTO room (id,name,building,capacity,room_type) VALUES ('X','Innovation Lab C','I',-5,'LAB')");
        bad("zero credit hours", "INSERT INTO subject (id,course_code,title,credit_hours,default_room_type) VALUES ('X','Z 1','t',0,'LAB')");
        bad("equipment with 0 units", "INSERT INTO equipment (id,name,item_type,unit_count) VALUES ('X','Cart','Laptop cart',0)");
        bad("unknown room type", "INSERT INTO room (id,name,building,capacity,room_type) VALUES ('X','R','B',10,'CAFE')");
        bad("term ends before it starts", "INSERT INTO term (id,name,start_date,end_date) VALUES ('X','Bad','2026-12-20','2026-09-01')");
        bad("published term without a publish time", "INSERT INTO term (id,name,start_date,end_date,status) VALUES ('X','P','2026-01-01','2026-02-01','PUBLISHED')");
        bad("meeting start == end (UC-02 5a)", "INSERT INTO section_meeting (id,section_id,pattern,day_of_week,start_time,end_time) VALUES ('X','S1','WEEKLY','TUESDAY','10:00','10:00')");
        bad("weekly meeting without a day", "INSERT INTO section_meeting (id,section_id,pattern,start_time,end_time) VALUES ('X','S1','WEEKLY','10:00','11:00')");
        bad("weekly meeting carrying a date", "INSERT INTO section_meeting (id,section_id,pattern,day_of_week,meeting_date,start_time,end_time) VALUES ('X','S1','WEEKLY','TUESDAY','2026-10-13','10:00','11:00')");
        bad("exam without a date", "INSERT INTO section_meeting (id,section_id,pattern,start_time,end_time) VALUES ('X','S1','EXAM','10:00','11:00')");
        bad("same instructor twice on a section", "INSERT INTO section_instructor VALUES ('S1','U02')");
        bad("two identical sections of a subject", "INSERT INTO section (id,term_id,subject_id,room_id) VALUES ('X','T1','SUB-CS340','R-LABB')");
        bad("booking with both room and equipment (TR2.6)", "INSERT INTO booking (id,requester_id,resource_type,room_id,equipment_id,start_time,end_time,purpose,created_at) VALUES ('X','U02','ROOM','R-F110','E-SPK','2026-10-15 10:00:00','2026-10-15 11:00:00','p','2026-10-01 09:00:00')");
        bad("booking with no resource", "INSERT INTO booking (id,requester_id,resource_type,start_time,end_time,purpose,created_at) VALUES ('X','U02','ROOM','2026-10-15 10:00:00','2026-10-15 11:00:00','p','2026-10-01 09:00:00')");
        bad("ROOM booking pointing at equipment", "INSERT INTO booking (id,requester_id,resource_type,equipment_id,start_time,end_time,purpose,created_at) VALUES ('X','U02','ROOM','E-SPK','2026-10-15 10:00:00','2026-10-15 11:00:00','p','2026-10-01 09:00:00')");
        bad("booking ends before it starts", "INSERT INTO booking (id,requester_id,resource_type,room_id,start_time,end_time,purpose,created_at) VALUES ('X','U02','ROOM','R-F110','2026-10-15 11:00:00','2026-10-15 10:00:00','p','2026-10-01 09:00:00')");
        bad("booking status outside the enum", "INSERT INTO booking (id,requester_id,resource_type,room_id,start_time,end_time,purpose,created_at,status) VALUES ('X','U02','ROOM','R-F110','2026-10-15 10:00:00','2026-10-15 11:00:00','p','2026-10-01 09:00:00','DONE')");
        bad("booking for a room that does not exist", "INSERT INTO booking (id,requester_id,resource_type,room_id,start_time,end_time,purpose,created_at) VALUES ('X','U02','ROOM','NOPE','2026-10-15 10:00:00','2026-10-15 11:00:00','p','2026-10-01 09:00:00')");
        bad("availability status outside the enum", "INSERT INTO instructor_availability (id,term_id,instructor_id,day_of_week,start_time,end_time,status) VALUES ('X','T1','U04','TUESDAY','09:00','10:00','MAYBE')");
        bad("duplicate availability block", "INSERT INTO instructor_availability (id,term_id,instructor_id,day_of_week,start_time,end_time,status) VALUES ('X','T1','U04','MONDAY','12:00','13:00','PREFER')");
        bad("same conflict fingerprint twice in a term", "INSERT INTO conflict (id,term_id,conflict_type,severity,explanation,fingerprint,detected_at) VALUES ('X','T1','CAPACITY_EXCEEDED','MEDIUM','e','INSTRUCTOR_UNAVAILABLE:S1:U04','2026-10-24 12:00:00')");
        bad("unknown conflict type", "INSERT INTO conflict (id,term_id,conflict_type,severity,explanation,fingerprint,detected_at) VALUES ('X','T1','WEATHER','LOW','e','f2','2026-10-24 12:00:00')");
        bad("audit entry without a reason (NFR3.3 / UC-05 4b)", "INSERT INTO audit_log_entry (id,conflict_id,conflict_type,conflict_explanation,user_id,occurred_at,reason) VALUES ('X','C1','CAPACITY_EXCEEDED','e','U01','2026-10-24 12:05:00',NULL)");
        bad("instructor insight that points at a room (TR2.9)", "INSERT INTO insight_suggestion (id,term_id,axis,room_id,message,created_at) VALUES ('X','T1','INSTRUCTOR_UTILIZATION','R-F110','m','2026-10-24 12:00:00')");
        bad("room insight with no room", "INSERT INTO insight_suggestion (id,term_id,axis,message,created_at) VALUES ('X','T1','ROOM_CAPACITY','m','2026-10-24 12:00:00')");
        bad("Needs review row without a reason (FR3.6)", "INSERT INTO import_row VALUES ('IB1',3,'NEEDS_REVIEW',NULL)");
        bad("same import row number twice", "INSERT INTO import_row VALUES ('IB1',1,'ADDED',NULL)");
        bad("delete a room that has bookings (FR2.5 keeps history)", "DELETE FROM room WHERE id = 'R-F110'");
        bad("delete a subject that has sections", "DELETE FROM subject WHERE id = 'SUB-CS340'");

        // 4. delete behaviour: removing a timetable clears its dependants but keeps the audit trail and bookings
        ok("delete the term (FR1.1)", "DELETE FROM term WHERE id = 'T1'");
        count("sections gone", "SELECT COUNT(*) FROM section", 0);
        count("meetings gone", "SELECT COUNT(*) FROM section_meeting", 0);
        count("conflicts gone", "SELECT COUNT(*) FROM conflict", 0);
        count("insights gone", "SELECT COUNT(*) FROM insight_suggestion", 0);
        count("audit entry survives (NFR3.3)", "SELECT COUNT(*) FROM audit_log_entry", 1);
        count("bookings untouched", "SELECT COUNT(*) FROM booking", 2);
        count("notice survives with its link cleared", "SELECT COUNT(*) FROM notice WHERE term_id IS NULL AND section_id IS NULL", 1);

        System.out.println("\n" + a[0].split(";")[0] + "  ->  " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void ok(String what, String sql) {
        try (Statement s = c.createStatement()) { s.execute(sql); passed++; }
        catch (SQLException e) { failed++; System.out.println("FAIL (should succeed): " + what + "\n      " + e.getMessage().split("\n")[0]); }
    }

    static void bad(String what, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            failed++; System.out.println("FAIL (was accepted, must be rejected): " + what);
        } catch (SQLException e) { passed++; }
    }

    static void count(String what, String sql, int expected) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            if (r.getInt(1) == expected) passed++;
            else { failed++; System.out.println("FAIL: " + what + " expected " + expected + " got " + r.getInt(1)); }
        } catch (SQLException e) { failed++; System.out.println("FAIL: " + what + " " + e.getMessage()); }
    }
}
