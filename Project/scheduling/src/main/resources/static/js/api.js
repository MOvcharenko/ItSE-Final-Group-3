// Talks to the REST API. The bearer token stays in sessionStorage, so it ends with the tab and each tab can be a
// different account. The role read from it only decides what to display: the server checks every request itself.

const TOKEN_KEY = 'scheduling.token';
const TERM_KEY = 'scheduling.termId';

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
}

/** The term last chosen in this tab, shared by the screens. A screen must check that the user can still see it. */
export function chosenTermId() {
  return sessionStorage.getItem(TERM_KEY);
}

export function rememberTerm(id) {
  sessionStorage.setItem(TERM_KEY, id);
}

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
