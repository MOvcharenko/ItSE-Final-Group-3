package signin;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Ends the sign-in session of an account that has been disabled or removed since it signed in, so it can no longer
 * get new tokens or use the account pages; the request then continues as signed out. Deliberately not a bean: as one,
 * Spring Boot would also run it outside the sign-in filter chains.
 */
class DisabledAccountFilter extends OncePerRequestFilter {

	private final AccountStore accounts;

	DisabledAccountFilter(AccountStore accounts) {
		this.accounts = accounts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Only a person signed in with the form; at the token endpoint the signed-in party is the client app instead.
		Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
		if (signedIn instanceof UsernamePasswordAuthenticationToken
				&& accounts.byId(signedIn.getName()).filter(Account::enabled).isEmpty()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			SecurityContextHolder.clearContext();
		}
		chain.doFilter(request, response);
	}

}
