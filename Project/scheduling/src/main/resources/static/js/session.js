// What this browser tab remembers. Everything is in sessionStorage, so it ends with the tab and each tab can be a
// different account. Only this module knows the storage keys.

const TOKEN_KEY = 'scheduling.token';
const TERM_KEY = 'scheduling.termId';
const OPEN_SECTION_KEY = 'scheduling.openSection';
const OIDC_STATE_KEY = 'scheduling.oidc.state';
const OIDC_VERIFIER_KEY = 'scheduling.oidc.verifier';
const ID_TOKEN_KEY = 'scheduling.oidc.idToken';
const PROMPT_LOGIN_KEY = 'scheduling.oidc.promptLogin';

export function accessToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

/** idToken is kept only to end the Core Platform session on "Switch account". */
export function rememberSignIn(token, idToken) {
  sessionStorage.setItem(TOKEN_KEY, token);
  if (idToken) sessionStorage.setItem(ID_TOKEN_KEY, idToken);
}

export function idToken() {
  return sessionStorage.getItem(ID_TOKEN_KEY);
}

function decodeClaims(token) {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const bytes = Uint8Array.from(atob(payload), (c) => c.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}

/** The signed-in user, or null if there is no usable token. The role only decides what to show. */
export function currentUser() {
  const token = accessToken();
  if (!token) return null;
  try {
    const claims = decodeClaims(token);
    if (claims.exp * 1000 <= Date.now()) return null;
    return { id: claims.sub, name: claims.name ?? claims.sub, role: claims.role };
  } catch {
    return null;
  }
}

/** Forgets the account and what it was looking at, and any sign-in still in progress. */
export function signOut() {
  [TOKEN_KEY, TERM_KEY, OPEN_SECTION_KEY, OIDC_STATE_KEY, OIDC_VERIFIER_KEY, ID_TOKEN_KEY]
    .forEach((key) => sessionStorage.removeItem(key));
}

/** The term last chosen in this tab, shared by the screens. A screen must check that the user can still see it. */
export function chosenTermId() {
  return sessionStorage.getItem(TERM_KEY);
}

export function rememberTerm(id) {
  sessionStorage.setItem(TERM_KEY, id);
}

/** Asks the Timetable screen to open this section's form when it next loads ("Open in timetable"). */
export function openSectionNext(termId, sectionId) {
  rememberTerm(termId);
  sessionStorage.setItem(OPEN_SECTION_KEY, sectionId);
}

/** The section to open, once: reading it clears it, so a later visit doesn't open it again. */
export function takeSectionToOpen() {
  const id = sessionStorage.getItem(OPEN_SECTION_KEY);
  sessionStorage.removeItem(OPEN_SECTION_KEY);
  return id;
}

/** The state and PKCE verifier of a sign-in in progress, until the browser comes back from the Core Platform. */
export function beginSignInFlow(state, verifier) {
  sessionStorage.setItem(OIDC_STATE_KEY, state);
  sessionStorage.setItem(OIDC_VERIFIER_KEY, verifier);
}

/** Reading the sign-in in progress also ends it, so a returned code can be used once only. */
export function takeSignInFlow() {
  const flow = { state: sessionStorage.getItem(OIDC_STATE_KEY), verifier: sessionStorage.getItem(OIDC_VERIFIER_KEY) };
  sessionStorage.removeItem(OIDC_STATE_KEY);
  sessionStorage.removeItem(OIDC_VERIFIER_KEY);
  return flow;
}

/** After "Switch account" without a Core Platform sign-out: the next sign-in asks for an account anyway. */
export function promptForAccountNext() {
  sessionStorage.setItem(PROMPT_LOGIN_KEY, 'true');
}

export function takePromptForAccount() {
  const prompt = Boolean(sessionStorage.getItem(PROMPT_LOGIN_KEY));
  sessionStorage.removeItem(PROMPT_LOGIN_KEY);
  return prompt;
}
