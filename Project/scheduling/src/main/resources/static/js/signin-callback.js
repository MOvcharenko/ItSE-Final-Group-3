// Handles the return from the Core Platform's OIDC sign-in. Exchanges the
// authorization code for an access token, stores it, and returns to the app.

import { completeOidcSignIn } from './auth.js';
import { banner, el } from './dom.js';
import { landing } from './shell.js';

const root = document.getElementById('app');

async function run() {
  try {
    await completeOidcSignIn();
    // Replace history so the back button does not return to this page with a spent code.
    location.replace('/');
  } catch (error) {
    document.title = 'Sign-in failed · Academic Scheduling';
    root.replaceChildren(landing('Sign-in did not complete',
      banner(error.message || 'Could not complete sign-in.'),
      el('p', { class: 'landing-note' },
        el('a', { href: '/' }, 'Return to sign-in'))));
  }
}

run();
