package schema;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Two threads approve two overlapping PENDING bookings for the same room, 40 rounds. Usage: <url> <user> <pw> <lock:true|false> */
public class ApprovalRaceCheck {
    static String url, user, pw;

    public static void main(String[] a) throws Exception {
        url = a[0]; user = a[1]; pw = a[2];
        boolean lock = Boolean.parseBoolean(a[3]);
        int bothApproved = 0, exactlyOne = 0, rounds = 40;
        try (Connection c = DriverManager.getConnection(url, user, pw); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS booking"); s.execute("DROP TABLE IF EXISTS room");
            s.execute("CREATE TABLE room (id VARCHAR(36) PRIMARY KEY)");
            s.execute("CREATE TABLE booking (id VARCHAR(36) PRIMARY KEY, room_id VARCHAR(36), start_time TIMESTAMP, end_time TIMESTAMP, status VARCHAR(10))");
            s.execute("INSERT INTO room VALUES ('R1')");
        }
        for (int i = 0; i < rounds; i++) {
            try (Connection c = DriverManager.getConnection(url, user, pw); Statement s = c.createStatement()) {
                s.execute("DELETE FROM booking");
                s.execute("INSERT INTO booking VALUES ('B1','R1','2026-10-14 17:00:00','2026-10-14 18:30:00','PENDING')");
                s.execute("INSERT INTO booking VALUES ('B2','R1','2026-10-14 17:30:00','2026-10-14 19:00:00','PENDING')"); // overlaps B1
            }
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<Boolean> f1 = ex.submit(() -> approve("B1", go, lock));
            Future<Boolean> f2 = ex.submit(() -> approve("B2", go, lock));
            go.countDown();
            f1.get(); f2.get(); ex.shutdown();
            try (Connection c = DriverManager.getConnection(url, user, pw); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT COUNT(*) FROM booking WHERE status='APPROVED'")) {
                r.next(); if (r.getInt(1) == 1) exactlyOne++; else if (r.getInt(1) == 2) bothApproved++;
            }
        }
        System.out.printf("lock=%s  rounds=%d  exactly-one-approved=%d  BOTH-approved(double booking)=%d%n", lock, rounds, exactlyOne, bothApproved);
    }

    /** The approve algorithm from the plan: lock the resource row, check overlap among APPROVED, then approve. */
    static boolean approve(String id, CountDownLatch go, boolean lock) throws Exception {
        go.await();
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                if (lock) s.execute("SELECT id FROM room WHERE id='R1' FOR UPDATE");
                int taken;
                try (ResultSet r = s.executeQuery(
                        "SELECT COUNT(*) FROM booking o, booking b WHERE b.id='" + id + "' AND o.room_id=b.room_id AND o.status='APPROVED' " +
                        "AND o.id<>b.id AND o.start_time < b.end_time AND b.start_time < o.end_time")) { r.next(); taken = r.getInt(1); }
                Thread.sleep(60);                                   // widen the window between check and write
                if (taken > 0) { c.rollback(); return false; }      // BookingCollisionException
                s.execute("UPDATE booking SET status='APPROVED' WHERE id='" + id + "'");
                c.commit(); return true;
            }
        }
    }
}
