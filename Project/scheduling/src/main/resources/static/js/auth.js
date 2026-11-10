// Signing in and out: the test-account sign-in (local, dev and test profiles) and the OpenID Connect
// authorization-code flow with PKCE against the Core Platform.
import { ApiError, send } from './http.js';
import {
  beginSignInFlow, idToken, promptForAccountNext, rememberSignIn, signOut, takePromptForAccount, takeSignInFlow,
} from './session.js';

/**
 * Where the Core Platform sends the browser back to: this page's own origin, so the app works however it was opened
 * (localhost or 127.0.0.1). Sign-in state lives in sessionStorage, which belongs to one origin, so the return address
 * must be the origin that started the sign-in.
 */
function redirectUri() {
  return `${location.origin}/signin-callback.html`;
}

/** A plain-language message for the error a Core Platform returns to the callback page (RFC 6749 §4.1.2.1). */
function signInErrorMessage(error) {
  if (error === 'access_denied') return 'Sign-in was cancelled.';
  if (error === 'login_required' || error === 'interaction_required') return 'Please sign in again.';
  return 'The Core Platform could not complete the sign-in. Please try again, or contact your administrator if it keeps happening.';
}

let authConfigPromise = null;

/**
 * Which sign-in flow to run: {@code dev} (test-account picker, /dev/token behind it) or {@code oidc}
 * (authorization-code + PKCE against the Core Platform). Fetched once per page load.
 */
export async function getAuthConfig() {
  if (!authConfigPromise) {
    authConfigPromise = fetch('/auth/config')
      .then(async (response) => {
        if (!response.ok) {
          // e.g. 503 when the Core Platform cannot be reached: the server says so in plain language
          const problem = await response.json().catch(() => null);
          throw new ApiError(response.status, problem?.detail || 'Could not load sign-in configuration.');
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

/** Test-account sign-in. Outside the local, dev and test profiles /dev/token does not exist. */
export async function signIn(userId) {
  const response = await send('/dev/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  });
  if (!response.ok) {
    throw new ApiError(response.status, 'Test accounts are only available when the app runs with the dev profile.');
  }
  rememberSignIn((await response.json()).accessToken);
}

/**
 * Signs out of this tab and, when the Core Platform has an end-session endpoint, out of the Core Platform too, so the
 * next sign-in asks for a password (otherwise "Switch account" would sign the same person straight back in).
 * Navigates away and returns true when it does; returns false when only this tab was signed out. Then the Core
 * Platform's session is still open, so the next sign-in asks it to prompt for an account anyway (prompt=login).
 * Without an ID token, logout names the client instead (OpenID Connect RP-initiated logout allows either).
 */
export function signOutOfCorePlatform(config) {
  const hint = idToken();
  signOut();
  if (!config || config.mode !== 'oidc') return false;
  if (!config.logoutUri) {
    promptForAccountNext();
    return false;
  }
  const url = new URL(config.logoutUri, location.origin);
  if (hint) url.searchParams.set('id_token_hint', hint);
  else url.searchParams.set('client_id', config.clientId);
  url.searchParams.set('post_logout_redirect_uri', `${location.origin}/`);
  location.assign(url.toString());
  return true;
}

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
  beginSignInFlow(state, verifier);

  const challenge = await s256(verifier);
  const url = new URL(config.authorizationEndpoint);
  url.searchParams.set('client_id', config.clientId);
  url.searchParams.set('redirect_uri', redirectUri());
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', config.scope);
  url.searchParams.set('state', state);
  url.searchParams.set('code_challenge', challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  if (takePromptForAccount()) {
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
  const expected = takeSignInFlow();
  if (!expected.state || state !== expected.state || !expected.verifier) {
    throw new ApiError(400, 'The sign-in response did not match this browser session. Please try again.');
  }

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri(),
    client_id: config.clientId,
    code_verifier: expected.verifier,
  });
  const response = await send(config.tokenEndpoint, {
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
  rememberSignIn(data.access_token, data.id_token);
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
