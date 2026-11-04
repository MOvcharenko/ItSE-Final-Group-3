package support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A throw-away database with the real Flyway migrations applied, for tests that need no Spring.
 * Default: in-memory H2 in PostgreSQL mode. To run the same tests on PostgreSQL pass
 * -Dtd1.jdbc.url=jdbc:postgresql://host/db -Dtd1.jdbc.user=... -Dtd1.jdbc.password=... (the public schema is wiped first).
 */
public final class TestDatabase {

	private TestDatabase() {
	}

	public static Connection open() throws Exception {
		String url = System.getProperty("td1.jdbc.url", "jdbc:h2:mem:td1_" + System.nanoTime()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
		Connection c = DriverManager.getConnection(url, System.getProperty("td1.jdbc.user", "sa"),
				System.getProperty("td1.jdbc.password", ""));
		c.setAutoCommit(true);
		if (url.startsWith("jdbc:postgresql")) {
			try (Statement st = c.createStatement()) {
				st.execute("DROP SCHEMA public CASCADE");
				st.execute("CREATE SCHEMA public");
			}
		}
		migrate(c);
		return c;
	}

	private static void migrate(Connection c) throws Exception {
		Path dir = Path.of(TestDatabase.class.getResource("/db/migration").toURI());
		List<Path> scripts;
		try (Stream<Path> s = Files.list(dir)) {
			scripts = s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
					.sorted(Comparator.comparingInt(TestDatabase::version)).toList();
		}
		for (Path script : scripts) {
			for (String stmt : Td1Loader.statements(Files.readString(script))) {
				try (Statement st = c.createStatement()) {
					st.execute(stmt);
				} catch (SQLException e) {
					throw new IllegalStateException(script.getFileName() + ": " + e.getMessage(), e);
				}
			}
		}
	}

	private static int version(Path p) {
		Matcher m = Pattern.compile("V(\\d+)__").matcher(p.getFileName().toString());
		m.find();
		return Integer.parseInt(m.group(1));
	}
}
