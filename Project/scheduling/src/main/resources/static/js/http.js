// HTTP to this app's own API: adds the token, turns errors into plain-language messages, and returns to sign-in on a
// 401. The server checks every request itself; nothing here decides what a user may do.
import { accessToken, signOut } from './session.js';

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

/** fetch, with a plain-language message when the server can't be reached at all. */
export async function send(path, options) {
  try {
    return await fetch(path, options);
  } catch {
    throw new ApiError(0, 'Could not reach the server. Check that it is running, then try again.');
  }
}

async function authorized(path, options = {}) {
  const headers = { ...options.headers, Authorization: `Bearer ${accessToken()}` };
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

/** What a call made by latestOnly answers once a newer call has started. Callers ignore it. */
export class Superseded extends Error {
  constructor() {
    super('A newer request has taken over.');
  }
}

/**
 * Wraps a loader so that only the latest call's answer counts: an older call, whether it worked or failed, rejects
 * with Superseded instead. drop() supersedes the calls still on their way, e.g. when what they were for has changed.
 */
export function latestOnly(loader) {
  let latest = 0;
  const call = async (...args) => {
    const mine = ++latest;
    let result;
    try {
      result = await loader(...args);
    } catch (error) {
      throw mine === latest ? error : new Superseded();
    }
    if (mine !== latest) throw new Superseded();
    return result;
  };
  call.drop = () => { latest++; };
  return call;
}
