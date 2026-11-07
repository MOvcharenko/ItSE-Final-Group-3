// Talks to the REST API. The bearer token stays in sessionStorage, so it ends with the tab and each tab can be a
// different account. The role read from it only decides what to display: the server checks every request itself.

const TOKEN_KEY = 'scheduling.token';
const TERM_KEY = 'scheduling.termId';
const OPEN_SECTION_KEY = 'scheduling.openSection';
const OIDC_STATE_KEY = 'scheduling.oidc.state';
const OIDC_VERIFIER_KEY = 'scheduling.oidc.verifier';
const ID_TOKEN_KEY = 'scheduling.oidc.idToken';
const PROMPT_LOGIN_KEY = 'scheduling.oidc.promptLogin';

let onSessionEnded = () => {};

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function setSessionEndedHandler(handler) {
  onSessionEnded = handler;
}

function decodeClaims(token) {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const bytes = Uint8Array.from(atob(payload), (c) => c.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}

/** The signed-in user, or null if there is no usable token. */
export function currentUser() {
  const token = sessionStorage.getItem(TOKEN_KEY);
  if (!token) return null;
  try {
    const claims = decodeClaims(token);
    if (claims.exp * 1000 <= Date.now()) return null;
    return { id: claims.sub, name: claims.name ?? claims.sub, role: claims.role };
  } catch {
    return null;
  }
}

export function signOut() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(TERM_KEY);
  sessionStorage.removeItem(OPEN_SECTION_KEY);
  sessionStorage.removeItem(OIDC_STATE_KEY);
  sessionStorage.removeItem(OIDC_VERIFIER_KEY);
  sessionStorage.removeItem(ID_TOKEN_KEY);
}

/**
 * Signs out of this tab and, when the Core Platform has an end-session endpoint, out of the Core Platform too, so the
 * next sign-in asks for a password (otherwise "Switch account" would sign the same person straight back in).
 * Navigates away and returns true when it does; returns false when only this tab was signed out. Then the Core
 * Platform's session is still open, so the next sign-in asks it to prompt for an account anyway (prompt=login).
 * Without an ID token, logout names the client instead (OpenID Connect RP-initiated logout allows either).
 */
export function signOutOfCorePlatform(config) {
  const idToken = sessionStorage.getItem(ID_TOKEN_KEY);
  signOut();
  if (!config || config.mode !== 'oidc') return false;
  if (!config.logoutUri) {
    sessionStorage.setItem(PROMPT_LOGIN_KEY, 'true');
    return false;
  }
  const url = new URL(config.logoutUri, location.origin);
  if (idToken) url.searchParams.set('id_token_hint', idToken);
  else url.searchParams.set('client_id', config.clientId);
  url.searchParams.set('post_logout_redirect_uri', `${location.origin}/`);
  location.assign(url.toString());
  return true;
}

/**
 * Where the Core Platform sends the browser back to: this page's own origin, so the app works however it was opened
 * (localhost or 127.0.0.1). Sign-in state lives in sessionStorage, which belongs to one origin, so the return address
 * must be the origin that started the sign-in.
 */
function redirectUri() {
  return `${location.origin}/signin-callback.html`;
}

/** The Core Platform's address: configured, or this app itself when it signs people in (the local profile). */
function issuerFor(config) {
  return (config.issuer || location.origin).replace(/\/$/, '');
}

/** A plain-language message for the error a Core Platform returns to the callback page (RFC 6749 §4.1.2.1). */
function signInErrorMessage(error) {
  if (error === 'access_denied') return 'Sign-in was cancelled.';
  if (error === 'login_required' || error === 'interaction_required') return 'Please sign in again.';
  return 'The Core Platform could not complete the sign-in. Please try again, or contact your administrator if it keeps happening.';
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

// --- sign-in configuration -------------------------------------------------

let authConfigPromise = null;

/**
 * Which sign-in flow to run: {@code dev} (test-account picker, /dev/token behind it) or {@code oidc}
 * (authorization-code + PKCE against the Core Platform). Fetched once per page load.
 */
export async function getAuthConfig() {
  if (!authConfigPromise) {
    authConfigPromise = fetch('/auth/config')
      .then((response) => {
        if (!response.ok) {
          throw new ApiError(response.status, 'Could not load sign-in configuration.');
        }
        return response.json();
      })
      .catch((error) => {
        authConfigPromise = null;
        throw error instanceof ApiError
          ? error
          : new ApiError(0, 'Could not load sign-in configuration.');
      });
  }
  return authConfigPromise;
}

// --- dev sign-in (dev and test profiles only) ------------------------------

/** Dev sign-in. In other profiles /dev/token does not exist, and signing in goes through the Core Platform. */
export async function signIn(userId) {
  const response = await send('/dev/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  });
  if (!response.ok) {
    throw new ApiError(response.status, 'Test accounts are only available when the app runs with the dev profile.');
  }
  sessionStorage.setItem(TOKEN_KEY, (await response.json()).accessToken);
}

// --- OIDC sign-in (mock and real Core Platform) -----------------------------

/**
 * Starts the OIDC authorization-code flow with PKCE. This navigates the browser away, so the
 * returned Promise does not resolve in this page: the flow completes on /signin-callback.html.
 */
export async function startOidcSignIn(config) {
  if (!config || config.mode !== 'oidc') {
    throw new ApiError(0, 'Sign-in is not configured. Contact your administrator.');
  }
  const verifier = randomString(64);
  const state = randomString(32);
  sessionStorage.setItem(OIDC_STATE_KEY, state);
  sessionStorage.setItem(OIDC_VERIFIER_KEY, verifier);

  const challenge = await s256(verifier);
  const url = new URL(`${issuerFor(config)}/oauth2/authorize`);
  url.searchParams.set('client_id', config.clientId);
  url.searchParams.set('redirect_uri', redirectUri());
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', config.scope);
  url.searchParams.set('state', state);
  url.searchParams.set('code_challenge', challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  if (sessionStorage.getItem(PROMPT_LOGIN_KEY)) {
    sessionStorage.removeItem(PROMPT_LOGIN_KEY);
    url.searchParams.set('prompt', 'login'); // after "Switch account": don't reuse the Core Platform's session
  }
  location.assign(url.toString());
}

/**
 * Completes the OIDC flow on the callback page: verifies state, exchanges the code for a token,
 * and stores it. Throws {@link ApiError} with a plain-language message on any failure.
 */
export async function completeOidcSignIn() {
  const config = await getAuthConfig();
  if (config.mode !== 'oidc') {
    throw new ApiError(400, 'This page is only used for Core Platform sign-in.');
  }
  const params = new URLSearchParams(location.search);
  const error = params.get('error');
  if (error) throw new ApiError(400, signInErrorMessage(error));
  const code = params.get('code');
  const state = params.get('state');
  if (!code || !state) throw new ApiError(400, 'Sign-in did not return a code.');
  const expectedState = sessionStorage.getItem(OIDC_STATE_KEY);
  const verifier = sessionStorage.getItem(OIDC_VERIFIER_KEY);
  sessionStorage.removeItem(OIDC_STATE_KEY);
  sessionStorage.removeItem(OIDC_VERIFIER_KEY);
  if (!expectedState || state !== expectedState || !verifier) {
    throw new ApiError(400, 'The sign-in response did not match this browser session. Please try again.');
  }

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri(),
    client_id: config.clientId,
    code_verifier: verifier,
  });
  const response = await send(`${issuerFor(config)}/oauth2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!response.ok) {
    throw new ApiError(response.status, 'The Core Platform did not accept the sign-in. Please try again.');
  }
  const data = await response.json();
  if (!data.access_token) {
    throw new ApiError(502, 'The Core Platform did not return a sign-in token. Please try again.');
  }
  sessionStorage.setItem(TOKEN_KEY, data.access_token);
  // Kept only to end the Core Platform session on "Switch account".
  if (data.id_token) sessionStorage.setItem(ID_TOKEN_KEY, data.id_token);
}

/** A random string of {@code length} characters from the PKCE-unreserved set (RFC 7636 §4.1). */
function randomString(length) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  let result = '';
  for (let i = 0; i < length; i++) result += alphabet[bytes[i] % alphabet.length];
  return result;
}

async function s256(input) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
  return base64url(new Uint8Array(digest));
}

function base64url(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// --- HTTP plumbing (unchanged below) ---------------------------------------

async function send(path, options) {
  try {
    return await fetch(path, options);
  } catch {
    throw new ApiError(0, 'Could not reach the server. Check that it is running, then try again.');
  }
}

async function authorized(path, options = {}) {
  const token = sessionStorage.getItem(TOKEN_KEY);
  const headers = { ...options.headers, Authorization: `Bearer ${token}` };
  const response = await send(path, { ...options, headers });
  if (response.ok) return response;
  if (response.status === 401) {
    signOut();
    onSessionEnded();
    throw new ApiError(401, 'Your session has ended. Please sign in again.');
  }
  throw new ApiError(response.status, await problemMessage(response));
}

/** The API's plain-language ProblemDetail text, or a general message when there is none (403 has no body). */
async function problemMessage(response) {
  try {
    const problem = await response.json();
    if (problem.errors) return Object.values(problem.errors).join(' ');
    if (problem.detail) return problem.detail;
  } catch {
    // no JSON body
  }
  return response.status === 403
    ? 'Your account does not have access to this.'
    : 'Something went wrong. Please try again.';
}

export async function getJson(path) {
  return (await authorized(path)).json();
}

async function sendJson(method, path, body) {
  const options = { method };
  if (body !== undefined) {
    options.headers = { 'Content-Type': 'application/json' };
    options.body = JSON.stringify(body);
  }
  const response = await authorized(path, options);
  return response.status === 204 ? null : response.json();
}

export const post = (path, body) => sendJson('POST', path, body);
export const put = (path, body) => sendJson('PUT', path, body);
export const patch = (path, body) => sendJson('PATCH', path, body);
export const del = (path) => sendJson('DELETE', path);

/** Sends a file as the multipart field "file". The browser sets the Content-Type, with its boundary. */
export async function upload(path, file) {
  const body = new FormData();
  body.append('file', file);
  return (await authorized(path, { method: 'POST', body })).json();
}

/** The API needs the bearer token, so a plain link cannot download a file. */
export async function download(path, fileName) {
  const blob = await (await authorized(path)).blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}