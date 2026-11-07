package signin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import domain.Role;
import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * The signin_account table. Passwords arrive here already encoded. A scheduler's departments and an instructor's home
 * department are the module's own profile rows (scheduler_department, app_user), so they are kept in one place only.
 */
@Repository
@Profile("local")
public class AccountStore {

	private static final String SELECT = """
			select a.id, a.username, a.password, a.name, a.email, a.role, a.admin, a.enabled, d.department,
			       u.department as home_department
			from signin_account a left join app_user u on u.id = a.id
			left join scheduler_department d on d.scheduler_id = a.id
			""";

	private final JdbcClient jdbc;
	private final AppUserRepository users;

	public AccountStore(JdbcClient jdbc, AppUserRepository users) {
		this.jdbc = jdbc;
		this.users = users;
	}

	public Optional<Account> byUsername(String username) {
		return query(SELECT + " where lower(a.username) = lower(:key)", username).stream().findFirst();
	}

	public Optional<Account> byId(String id) {
		return query(SELECT + " where a.id = :key", id).stream().findFirst();
	}

	public List<Account> all() {
		return query(SELECT + " order by a.id", null);
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

	/**
	 * Also creates a scheduler's or instructor's scheduling profile (app_user) if missing, and grants a scheduler's
	 * departments. Students get none: the SIS owns them (TR1.3), and every profile is offered as an instructor.
	 */
	@Transactional
	public void create(Account account) {
		jdbc.sql("""
				insert into signin_account (id, username, password, name, email, role, admin, enabled)
				values (:id, :username, :password, :name, :email, :role, :admin, :enabled)
				""")
				.param("id", account.id())
				.param("username", account.username())
				.param("password", account.password())
				.param("name", account.name())
				.param("email", account.email())
				.param("role", account.role().name())
				.param("admin", account.admin())
				.param("enabled", account.enabled())
				.update();
		if (account.role() == Role.STUDENT) {
			return;
		}
		AppUserEntity profile = users.findById(account.id())
				.orElseGet(() -> new AppUserEntity(account.id(), account.name(), account.email(), account.homeDepartment()));
		account.departments().forEach(profile::authorizeDepartment);
		// flushed now, so this transaction's own reads above (plain SQL) see the departments
		users.saveAndFlush(profile);
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
