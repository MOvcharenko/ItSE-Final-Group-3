package accounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The signin_account and account_department tables. Passwords arrive here already encoded. */
@Repository
public class AccountStore {

	private static final String SELECT = """
			select a.id, a.username, a.password, a.name, a.email, a.role, a.admin, a.enabled, a.home_department,
			       d.department
			from signin_account a left join account_department d on d.account_id = a.id
			""";

	private final JdbcClient jdbc;

	public AccountStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Account> byUsername(String username) {
		return query(SELECT + " where lower(a.username) = lower(:key)", username).stream().findFirst();
	}

	public Optional<Account> byId(String id) {
		return query(SELECT + " where a.id = :key", id).stream().findFirst();
	}

	public List<Account> all() {
		return query(SELECT + " order by a.id, d.department", null);
	}

	public long count() {
		return jdbc.sql("select count(*) from signin_account").query(Long.class).single();
	}

	public boolean usernameTaken(String username) {
		return jdbc.sql("select count(*) from signin_account where lower(username) = lower(:u)").param("u", username)
				.query(Long.class).single() > 0;
	}

	public boolean idTaken(String id) {
		return jdbc.sql("select count(*) from signin_account where id = :id").param("id", id).query(Long.class).single() > 0;
	}

	@Transactional
	public void create(Account account) {
		jdbc.sql("""
				insert into signin_account (id, username, password, name, email, role, home_department, admin, enabled)
				values (:id, :username, :password, :name, :email, :role, :home, :admin, :enabled)
				""")
				.param("id", account.id())
				.param("username", account.username())
				.param("password", account.password())
				.param("name", account.name())
				.param("email", account.email())
				.param("role", account.role().name())
				.param("home", account.homeDepartment())
				.param("admin", account.admin())
				.param("enabled", account.enabled())
				.update();
		for (String department : account.departments()) {
			jdbc.sql("insert into account_department (account_id, department) values (:id, :department)")
					.param("id", account.id())
					.param("department", department)
					.update();
		}
	}

	public void updatePassword(String id, String encodedPassword) {
		jdbc.sql("update signin_account set password = :p where id = :id").param("p", encodedPassword).param("id", id).update();
	}

	public void setEnabled(String id, boolean enabled) {
		jdbc.sql("update signin_account set enabled = :e where id = :id").param("e", enabled).param("id", id).update();
	}

	/** Rows come one per department; folds them back into one account each, keeping order. */
	private List<Account> query(String sql, String key) {
		var statement = jdbc.sql(sql);
		if (key != null) {
			statement = statement.param("key", key);
		}
		Map<String, Row> rows = new LinkedHashMap<>();
		statement.query((rs, n) -> {
			String id = rs.getString("id");
			Row row = rows.get(id);
			if (row == null) {
				row = new Row(id, rs.getString("username"), rs.getString("password"), rs.getString("name"),
						rs.getString("email"), Role.valueOf(rs.getString("role")), rs.getBoolean("admin"), rs.getBoolean("enabled"),
						new ArrayList<>(), rs.getString("home_department"));
				rows.put(id, row);
			}
			String department = rs.getString("department");
			if (department != null) {
				row.departments().add(department);
			}
			return row;
		}).list();
		return rows.values().stream().map(Row::toAccount).toList();
	}

	private record Row(String id, String username, String password, String name, String email, Role role,
			boolean admin, boolean enabled, List<String> departments, String homeDepartment) {

		Account toAccount() {
			return new Account(id, username, password, name, email, role, admin, enabled, departments, homeDepartment);
		}

	}

}
