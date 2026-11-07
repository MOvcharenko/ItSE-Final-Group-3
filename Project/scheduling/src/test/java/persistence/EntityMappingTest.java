package persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Poor man's {@code spring.jpa.hibernate.ddl-auto=validate} that needs no Spring and no Hibernate: applies the Flyway
 * migrations to an in-memory H2 (PostgreSQL mode) and compares every entity's annotations with the real tables.
 * It catches the mistakes that otherwise surface as a 500 on the first request:
 *  - a column or table name that does not exist
 *  - NOT NULL in the database but nullable in the entity (or the other way round)
 *  - a text field longer than its column
 *  - an enum without EnumType.STRING, or with a value the CHECK constraint does not allow
 *  - a NOT NULL column without default that no field fills (every insert would fail)
 * Add every new entity to {@link #entities()}.
 */
class EntityMappingTest {

	private static Connection db;

	static Stream<Class<?>> entities() {
		return Stream.of(AppUserEntity.class, RoomEntity.class, EquipmentEntity.class, SubjectEntity.class,
				BookingEntity.class, ImportBatchEntity.class, TermEntity.class, SectionEntity.class,
				NoticeEntity.class, ConflictEntity.class, AuditLogEntryEntity.class,
				InstructorAvailabilityEntity.class, AvailabilitySubmissionEntity.class,
				InstructorEquipmentNeedEntity.class, InsightSuggestionEntity.class);
	}

	@BeforeAll
	static void migrate() throws Exception {
		db = DriverManager.getConnection("jdbc:h2:mem:mapping" + System.nanoTime()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "");
		Path dir = Path.of(EntityMappingTest.class.getResource("/db/migration").toURI());
		List<Path> scripts;
		try (Stream<Path> s = Files.list(dir)) {
			scripts = s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
					.sorted(Comparator.comparingInt(EntityMappingTest::version)).toList();
		}
		for (Path script : scripts) {
			StringBuilder sql = new StringBuilder();
			for (String line : Files.readString(script).split("\n")) {
				int c = line.indexOf("--");
				sql.append(c >= 0 ? line.substring(0, c) : line).append('\n');
			}
			try (Statement st = db.createStatement()) {
				for (String stmt : sql.toString().split(";")) {
					if (!stmt.isBlank()) {
						st.execute(stmt);
					}
				}
			}
		}
	}

	private static int version(Path p) {
		Matcher m = Pattern.compile("V(\\d+)__").matcher(p.getFileName().toString());
		m.find();
		return Integer.parseInt(m.group(1));
	}

	@AfterAll
	static void close() throws SQLException {
		db.close();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("entities")
	@DisplayName("Entity matches the V1 schema")
	void entityMatchesSchema(Class<?> entity) throws Exception {
		String table = entity.getAnnotation(Table.class).name();
		Map<String, Col> cols = columns(table);
		assertFalse(cols.isEmpty(), entity.getSimpleName() + ": table '" + table + "' does not exist");

		List<String> problems = new ArrayList<>();
		Set<String> mapped = new HashSet<>();
		for (Field f : fieldsOf(entity)) {
			if (f.isAnnotationPresent(ElementCollection.class)) {
				checkCollection(entity, f, problems);
			} else {
				checkField(entity, table, cols, f, mapped, problems);
			}
		}
		unmappedRequired(entity.getSimpleName(), table, cols, mapped, problems);
		assertTrue(problems.isEmpty(), "\n  " + String.join("\n  ", problems));
	}

	// ---------------------------------------------------------------------------------------------

	private static void checkCollection(Class<?> owner, Field f, List<String> problems) throws Exception {
		String where = owner.getSimpleName() + "." + f.getName();
		CollectionTable ct = f.getAnnotation(CollectionTable.class);
		if (ct == null || ct.joinColumns().length != 1 || ct.joinColumns()[0].name().isEmpty()) {
			problems.add(where + ": give @CollectionTable an explicit name and one named @JoinColumn");
			return;
		}
		Map<String, Col> cols = columns(ct.name());
		if (cols.isEmpty()) {
			problems.add(where + ": collection table '" + ct.name() + "' does not exist");
			return;
		}
		Set<String> mapped = new HashSet<>();
		String join = ct.joinColumns()[0].name();
		Col jc = cols.get(join);
		if (jc == null) {
			problems.add(where + ": join column '" + join + "' is not in " + ct.name());
		} else {
			mapped.add(join);
		}
		Class<?> element = (Class<?>) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
		if (element.isAnnotationPresent(Embeddable.class)) {
			for (Field ef : fieldsOf(element)) {
				checkField(element, ct.name(), cols, ef, mapped, problems);
			}
		} else {
			checkField(owner, ct.name(), cols, f, mapped, problems);
		}
		unmappedRequired(where, ct.name(), cols, mapped, problems);
	}

	private static void checkField(Class<?> owner, String table, Map<String, Col> cols, Field f, Set<String> mapped,
			List<String> problems) throws Exception {
		String where = owner.getSimpleName() + "." + f.getName();
		String name;
		boolean nullable;
		int length = 255;
		if (f.isAnnotationPresent(ManyToOne.class)) {
			JoinColumn jc = f.getAnnotation(JoinColumn.class);
			if (jc == null || jc.name().isEmpty()) {
				problems.add(where + ": @ManyToOne needs @JoinColumn(name = ...)");
				return;
			}
			name = jc.name();
			nullable = jc.nullable();
		} else {
			Column c = f.getAnnotation(Column.class);
			if (c == null || c.name().isEmpty()) {
				problems.add(where + ": needs @Column(name = ...) so the column name never depends on a naming strategy");
				return;
			}
			name = c.name();
			nullable = c.nullable();
			length = c.length();
		}
		Col col = cols.get(name);
		if (col == null) {
			problems.add(where + ": column '" + name + "' does not exist in " + table);
			return;
		}
		mapped.add(name);
		if (col.notNull && nullable) {
			problems.add(where + ": " + table + "." + name + " is NOT NULL, so declare nullable = false");
		}
		if (!col.notNull && !nullable) {
			problems.add(where + ": " + table + "." + name + " allows NULL, but the entity says nullable = false");
		}
		if (f.getType() == String.class && length != 255 && col.size > 0 && length > col.size) {
			problems.add(where + ": length " + length + " is larger than the column size " + col.size);
		}
		if (f.getType().isEnum()) {
			Enumerated en = f.getAnnotation(Enumerated.class);
			if (en == null || en.value() != EnumType.STRING) {
				problems.add(where + ": enum must be @Enumerated(EnumType.STRING); the table stores text");
			} else {
				enumValuesAllowed(where, table, name, f.getType(), problems);
			}
		}
	}

	private static void enumValuesAllowed(String where, String table, String column, Class<?> enumType,
			List<String> problems) throws SQLException {
		List<String> clauses = new ArrayList<>();
		try (PreparedStatement ps = db.prepareStatement(
				"SELECT cc.check_clause FROM information_schema.check_constraints cc "
						+ "JOIN information_schema.table_constraints tc ON cc.constraint_name = tc.constraint_name "
						+ "AND cc.constraint_schema = tc.constraint_schema WHERE LOWER(tc.table_name) = ?")) {
			ps.setString(1, table.toLowerCase(Locale.ROOT));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					clauses.add(rs.getString(1));
				}
			}
		}
		List<String> forColumn = clauses.stream()
				.filter(c -> c.toLowerCase(Locale.ROOT).contains(column.toLowerCase(Locale.ROOT)))
				.filter(c -> Stream.of(enumType.getEnumConstants()).anyMatch(e -> c.contains("'" + e + "'"))).toList();
		if (forColumn.isEmpty()) {
			problems.add(where + ": no CHECK constraint on " + table + "." + column + " lists the " + enumType.getSimpleName() + " values");
			return;
		}
		for (Object constant : enumType.getEnumConstants()) {
			boolean allowed = forColumn.stream().anyMatch(c -> c.contains("'" + constant + "'"));
			if (!allowed) {
				problems.add(where + ": " + enumType.getSimpleName() + "." + constant + " is not allowed by the CHECK on "
						+ table + "." + column + " (inserting it would fail)");
			}
		}
	}

	private static void unmappedRequired(String where, String table, Map<String, Col> cols, Set<String> mapped,
			List<String> problems) {
		cols.forEach((name, col) -> {
			if (col.notNull && !col.hasDefault && !mapped.contains(name)) {
				problems.add(where + ": " + table + "." + name + " is NOT NULL without a default but no field maps it,"
						+ " so every insert would fail");
			}
		});
	}

	private record Col(boolean notNull, int size, boolean hasDefault) {
	}

	private static Map<String, Col> columns(String table) throws SQLException {
		Map<String, Col> cols = new HashMap<>();
		try (ResultSet rs = db.getMetaData().getColumns(null, null, table.toLowerCase(Locale.ROOT), null)) {
			while (rs.next()) {
				if ("information_schema".equalsIgnoreCase(rs.getString("TABLE_SCHEM"))) {
					continue;
				}
				cols.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), new Col(
						"NO".equals(rs.getString("IS_NULLABLE")), rs.getInt("COLUMN_SIZE"), rs.getString("COLUMN_DEF") != null));
			}
		}
		return cols;
	}

	/** Persistent fields of the class and its @MappedSuperclass parents. */
	private static List<Field> fieldsOf(Class<?> c) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && !f.isAnnotationPresent(Transient.class)) {
					fields.add(f);
				}
			}
		}
		return fields;
	}
}
