package security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs {@link ProfileSync} for each request that carries a valid token. A failure is logged and the request goes on:
 * a profile that is a moment out of date must not lock anyone out. Deliberately not a bean: as one, Spring Boot would
 * also run it outside the security filter chain, before the token is checked.
 */
class ProfileSyncFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ProfileSyncFilter.class);

	private final ProfileSync profiles;

	ProfileSyncFilter(ProfileSync profiles) {
		this.profiles = profiles;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
			try {
				profiles.sync(jwt);
			}
			catch (DataAccessException e) {
				log.warn("Could not update the scheduling profile of {}.", jwt.getSubject(), e);
			}
		}
		chain.doFilter(request, response);
	}

}
