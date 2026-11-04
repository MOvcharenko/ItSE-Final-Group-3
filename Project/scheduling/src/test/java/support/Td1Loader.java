package support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

/**
 * Puts the database into the known state TP3 asks for: {@link #reset} empties every application table,
 * {@link #load} inserts TD-1. It is plain JDBC on purpose: it needs no entities (so it works before every stream
 * has written theirs) and no Spring.
 *
 * <pre>
 * // in a @Transactional Spring test: join the test's transaction so everything rolls back afterwards
 * Connection c = DataSourceUtils.getConnection(dataSource);
 * Td1Loader.resetAndLoad(c);
 *
 * // in a test that is NOT transactional (e.g. a concurrency test): commits by itself
 * Td1Loader.resetAndLoad(dataSource);
 * </pre>
 *
 * The Flyway history table is never touched. When a migration adds a table, add it to {@link #TABLES_CHILD_FIRST};
 * Td1LoaderTest fails until you do.
 */
public final class Td1Loader {

	private static final String SCRIPT = "/td1/td1.sql";

	/** Every application table, children before parents, so deletes never violate a foreign key. */
	static final List<String> TABLES_CHILD_FIRST = List.of(
			"notice", "insight_suggestion", "audit_log_entry", "conflict_section", "conflict",
			"instructor_equipment_need", "availability_submission", "instructor_availability",
			"booking", "section_equipment", "section_instructor", "section_meeting", "section",
			"import_row", "import_batch", "term",
			"subject_equipment", "subject", "room_fixed_equipment", "room", "equipment",
			"scheduler_department", "app_user");

	private Td1Loader() {
	}

	/** Empties every application table. Uses the caller's connection and does not commit. */
	public static void reset(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			for (String table : TABLES_CHILD_FIRST) {
				st.executeUpdate("DELETE FROM " + table);
			}
		}
	}

	/** Inserts TD-1. The tables must be empty (call {@link #reset} first). Does not commit. */
	public static void load(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			for (String statement : statements(readScript())) {
				st.executeUpdate(statement);
			}
		}
	}

	public static void resetAndLoad(Connection c) throws SQLException {
		reset(c);
		load(c);
	}

	/** For tests that are not transactional: own connection, committed. */
	public static void resetAndLoad(DataSource dataSource) throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try {
				resetAndLoad(c);
				c.commit();
			} catch (SQLException | RuntimeException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(auto);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------

	private static String readScript() {
		try (InputStream in = Td1Loader.class.getResourceAsStream(SCRIPT)) {
			if (in == null) {
				throw new IllegalStateException("Missing test resource " + SCRIPT);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read " + SCRIPT, e);
		}
	}

	/** Drops "--" comments and splits on semicolons. The script keeps both characters out of its strings. */
	static List<String> statements(String script) {
		StringBuilder sql = new StringBuilder();
		for (String line : script.split("\n")) {
			int comment = line.indexOf("--");
			sql.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
		}
		return java.util.Arrays.stream(sql.toString().split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}
}
