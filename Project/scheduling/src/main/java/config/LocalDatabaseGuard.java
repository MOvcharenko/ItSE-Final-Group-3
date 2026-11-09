package config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the dev-account picker against a database that is not local.
 * The picker issues real Scheduler tokens for fixed ids; against a shared or
 * production database that is a security problem. The <code>shared</code>
 * profile is exempt: it is deliberately pointed at a shared database, and is
 * documented as never-to-produce.
 */
@Component
@Profile({ "local", "dev" })
class LocalDatabaseGuard {

	LocalDatabaseGuard(DataSource dataSource) throws Exception {
		String url = dataSource.getConnection().getMetaData().getURL();
		boolean local = url.startsWith("jdbc:h2:")
				|| url.startsWith("jdbc:postgresql://localhost:")
				|| url.startsWith("jdbc:postgresql://127.0.0.1:");
		if (!local) {
			throw new IllegalStateException(
					"The local/dev profile may only start against a local database. "
					+ "Use the 'shared' profile for the shared Supabase project. "
					+ "Actual JDBC URL: " + url);
		}
	}

}