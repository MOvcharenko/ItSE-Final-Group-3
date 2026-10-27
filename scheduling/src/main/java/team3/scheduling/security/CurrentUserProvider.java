package team3.scheduling.security;

/** Cross-stream contract (owner: Dev A). Returns the user making the current request. */
public interface CurrentUserProvider {

	CurrentUser get();

}
