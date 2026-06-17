'use strict';

const crypto = require('crypto');
const fs = require('fs/promises');
const http = require('http');
const path = require('path');

const BACKEND_HOSTED = 'hosted';
const BACKEND_NUVIO = 'nuvio';
const BACKENDS = new Set([BACKEND_HOSTED, BACKEND_NUVIO]);
const DEFAULT_STATE_FILE = path.join(__dirname, 'data', 'state.json');

const env = process.env;

const serverConfig = {
  host: env.NUVIO_SWITCH_HOST || env.HOST || '0.0.0.0',
  port: parsePort(env.NUVIO_SWITCH_PORT || env.PORT || '3000'),
  stateFile: resolveStateFile(env.NUVIO_SWITCH_STATE_FILE || env.STATE_FILE),
  manifestVersion: parsePositiveInteger(env.NUVIO_SWITCH_VERSION || '1', 'NUVIO_SWITCH_VERSION'),
  defaultBackend: parseBackend(env.NUVIO_SWITCH_DEFAULT_BACKEND || 'hosted'),
  forceLogoutOnChange: parseBoolean(env.NUVIO_SWITCH_FORCE_LOGOUT_ON_CHANGE, true),
  adminUsername: env.NUVIO_SWITCH_ADMIN_USERNAME || env.ADMIN_USERNAME || 'admin',
  adminPassword: env.NUVIO_SWITCH_ADMIN_PASSWORD || env.ADMIN_PASSWORD || '',
  hosted: {
    displayName: env.NUVIO_SWITCH_HOSTED_DISPLAY_NAME || 'Hosted',
    supabaseUrl:
      env.NUVIO_SWITCH_HOSTED_SUPABASE_URL ||
      env.NUVIO_SWITCH_HOSTED_URL ||
      'https://dpyhjjcoabcglfmgecug.supabase.co',
    anonKey:
      env.NUVIO_SWITCH_HOSTED_SUPABASE_ANON_KEY ||
      env.NUVIO_SWITCH_HOSTED_ANON_KEY ||
      '',
    avatarPublicBaseUrl:
      env.NUVIO_SWITCH_HOSTED_AVATAR_PUBLIC_BASE_URL ||
      'https://dpyhjjcoabcglfmgecug.supabase.co/storage/v1/object/public/avatars',
    schemaVersion: parsePositiveInteger(
      env.NUVIO_SWITCH_HOSTED_SCHEMA_VERSION || '1',
      'NUVIO_SWITCH_HOSTED_SCHEMA_VERSION'
    ),
  },
  nuvio: {
    displayName: env.NUVIO_SWITCH_NUVIO_DISPLAY_NAME || 'Nuvio',
    supabaseUrl:
      env.NUVIO_SWITCH_NUVIO_SUPABASE_URL ||
      env.NUVIO_SWITCH_NUVIO_URL ||
      'https://api.nuvio.tv',
    anonKey:
      env.NUVIO_SWITCH_NUVIO_SUPABASE_ANON_KEY ||
      env.NUVIO_SWITCH_NUVIO_ANON_KEY ||
      'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI',
    avatarPublicBaseUrl:
      env.NUVIO_SWITCH_NUVIO_AVATAR_PUBLIC_BASE_URL ||
      'https://api.nuvio.tv/storage/v1/object/public/avatars',
    schemaVersion: parsePositiveInteger(
      env.NUVIO_SWITCH_NUVIO_SCHEMA_VERSION || '1',
      'NUVIO_SWITCH_NUVIO_SCHEMA_VERSION'
    ),
  },
};

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((error) => {
    console.error(error);
    if (error.statusCode === 413) {
      sendJson(res, 413, { error: 'request_body_too_large' });
      return;
    }

    sendJson(res, 500, { error: 'internal_server_error' });
  });
});

server.listen(serverConfig.port, serverConfig.host, () => {
  console.log(
    `Nuvio sync switch listening on http://${serverConfig.host}:${serverConfig.port}`
  );
  console.log(`State file: ${serverConfig.stateFile}`);
});

async function handleRequest(req, res) {
  const requestUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = normalizePath(requestUrl.pathname);

  if (req.method === 'GET' && pathname === '/health') {
    sendJson(res, 200, { ok: true });
    return;
  }

  if (req.method === 'GET' && pathname === '/config.json') {
    const state = await readState();
    sendJson(res, 200, buildManifest(state));
    return;
  }

  if (req.method === 'GET' && pathname === '/admin') {
    if (!requireAdmin(req, res)) return;

    const state = await readState();
    sendHtml(res, 200, renderAdminPage(state));
    return;
  }

  if (req.method === 'POST' && pathname === '/admin/backend') {
    if (!requireAdmin(req, res)) return;

    const body = await readRequestBody(req);
    const backend = parseBackendFromRequest(req, body);
    if (!backend) {
      sendJson(res, 400, {
        error: 'invalid_backend',
        message: 'Expected activeBackend or backend to be hosted or nuvio.',
      });
      return;
    }

    const state = await writeBackend(backend);
    if (prefersHtml(req)) {
      sendRedirect(res, '/admin');
      return;
    }

    sendJson(res, 200, {
      ok: true,
      activeBackend: state.activeBackend,
      revision: state.revision,
    });
    return;
  }

  if (pathname === '/admin' || pathname === '/admin/backend') {
    sendJson(res, 405, { error: 'method_not_allowed' }, { Allow: 'GET, POST' });
    return;
  }

  sendJson(res, 404, { error: 'not_found' });
}

function normalizePath(pathname) {
  if (pathname.length > 1 && pathname.endsWith('/')) {
    return pathname.slice(0, -1);
  }

  return pathname;
}

function buildManifest(state) {
  return {
    version: serverConfig.manifestVersion,
    activeBackend: state.activeBackend,
    revision: String(state.revision),
    forceLogoutOnChange: serverConfig.forceLogoutOnChange,
    backends: {
      hosted: publicBackendConfig(serverConfig.hosted),
      nuvio: publicBackendConfig(serverConfig.nuvio),
    },
  };
}

function publicBackendConfig(config) {
  return {
    displayName: config.displayName,
    supabaseUrl: normalizeBaseUrl(config.supabaseUrl),
    anonKey: config.anonKey,
    avatarPublicBaseUrl: normalizeBaseUrl(config.avatarPublicBaseUrl),
    schemaVersion: config.schemaVersion,
  };
}

async function readState() {
  try {
    const rawState = await fs.readFile(serverConfig.stateFile, 'utf8');
    return normalizeState(JSON.parse(rawState));
  } catch (error) {
    if (error.code === 'ENOENT') {
      const initialState = {
        activeBackend: serverConfig.defaultBackend,
        revision: 1,
        updatedAt: new Date().toISOString(),
      };
      await writeState(initialState);
      return initialState;
    }

    if (error instanceof SyntaxError) {
      throw new Error(`State file is not valid JSON: ${serverConfig.stateFile}`);
    }

    throw error;
  }
}

function normalizeState(state) {
  const activeBackend = parseBackend(state.activeBackend);
  const revision = Number.isInteger(state.revision) && state.revision > 0 ? state.revision : 1;

  return {
    activeBackend,
    revision,
    updatedAt: typeof state.updatedAt === 'string' ? state.updatedAt : undefined,
  };
}

async function writeBackend(activeBackend) {
  const currentState = await readState();
  const nextRevision =
    currentState.activeBackend === activeBackend
      ? currentState.revision
      : currentState.revision + 1;

  const nextState = {
    activeBackend,
    revision: nextRevision,
    updatedAt: new Date().toISOString(),
  };

  await writeState(nextState);
  return nextState;
}

async function writeState(state) {
  const stateDir = path.dirname(serverConfig.stateFile);
  const tempFile = path.join(
    stateDir,
    `.${path.basename(serverConfig.stateFile)}.${process.pid}.${crypto.randomUUID()}.tmp`
  );

  await fs.mkdir(stateDir, { recursive: true });
  await fs.writeFile(tempFile, `${JSON.stringify(state, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  });
  await fs.rename(tempFile, serverConfig.stateFile);
}

function requireAdmin(req, res) {
  if (!serverConfig.adminPassword) {
    sendJson(res, 503, {
      error: 'admin_password_not_configured',
      message: 'Set NUVIO_SWITCH_ADMIN_PASSWORD before using admin endpoints.',
    });
    return false;
  }

  const authHeader = req.headers.authorization || '';
  if (!authHeader.startsWith('Basic ')) {
    sendAuthRequired(res);
    return false;
  }

  const credentials = Buffer.from(authHeader.slice('Basic '.length), 'base64').toString('utf8');
  const separatorIndex = credentials.indexOf(':');
  const username = separatorIndex >= 0 ? credentials.slice(0, separatorIndex) : '';
  const password = separatorIndex >= 0 ? credentials.slice(separatorIndex + 1) : '';

  if (
    timingSafeEqual(username, serverConfig.adminUsername) &&
    timingSafeEqual(password, serverConfig.adminPassword)
  ) {
    return true;
  }

  sendAuthRequired(res);
  return false;
}

function timingSafeEqual(input, expected) {
  const inputBuffer = Buffer.from(input);
  const expectedBuffer = Buffer.from(expected);

  if (inputBuffer.length !== expectedBuffer.length) {
    return false;
  }

  return crypto.timingSafeEqual(inputBuffer, expectedBuffer);
}

function sendAuthRequired(res) {
  sendText(res, 401, 'Authentication required.\n', {
    'WWW-Authenticate': 'Basic realm="Nuvio Sync Switch", charset="UTF-8"',
  });
}

async function readRequestBody(req) {
  const chunks = [];
  let bytesRead = 0;
  const maxBytes = 16 * 1024;

  for await (const chunk of req) {
    bytesRead += chunk.length;
    if (bytesRead > maxBytes) {
      const error = new Error('Request body too large.');
      error.statusCode = 413;
      throw error;
    }

    chunks.push(chunk);
  }

  return Buffer.concat(chunks).toString('utf8');
}

function parseBackendFromRequest(req, body) {
  const contentType = String(req.headers['content-type'] || '').split(';')[0].trim();

  if (contentType === 'application/json') {
    try {
      const parsed = JSON.parse(body || '{}');
      return safeParseBackend(parsed.activeBackend || parsed.backend);
    } catch {
      return null;
    }
  }

  const formData = new URLSearchParams(body);
  return safeParseBackend(formData.get('activeBackend') || formData.get('backend'));
}

function parseBackend(value) {
  const backend = String(value || '').trim().toLowerCase();
  if (BACKENDS.has(backend)) {
    return backend;
  }

  throw new Error(`Invalid backend "${value}". Expected hosted or nuvio.`);
}

function safeParseBackend(value) {
  const backend = String(value || '').trim().toLowerCase();
  return BACKENDS.has(backend) ? backend : null;
}

function parseBoolean(value, defaultValue) {
  if (value === undefined || value === '') {
    return defaultValue;
  }

  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function parsePort(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Invalid port "${value}".`);
  }

  return port;
}

function parsePositiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1) {
    throw new Error(`Invalid ${name} "${value}".`);
  }

  return parsed;
}

function normalizeBaseUrl(value) {
  return String(value || '').trim().replace(/\/+$/, '');
}

function resolveStateFile(value) {
  if (!value) {
    return DEFAULT_STATE_FILE;
  }

  if (path.isAbsolute(value)) {
    return value;
  }

  return path.resolve(process.cwd(), value);
}

function prefersHtml(req) {
  const accept = req.headers.accept || '';
  const contentType = req.headers['content-type'] || '';
  return contentType.includes('application/x-www-form-urlencoded') && accept.includes('text/html');
}

function sendJson(res, statusCode, payload, headers = {}) {
  send(res, statusCode, JSON.stringify(payload, null, 2), {
    'Content-Type': 'application/json; charset=utf-8',
    ...headers,
  });
}

function sendHtml(res, statusCode, body, headers = {}) {
  send(res, statusCode, body, {
    'Content-Type': 'text/html; charset=utf-8',
    ...headers,
  });
}

function sendText(res, statusCode, body, headers = {}) {
  send(res, statusCode, body, {
    'Content-Type': 'text/plain; charset=utf-8',
    ...headers,
  });
}

function sendRedirect(res, location) {
  res.writeHead(303, {
    Location: location,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end();
}

function send(res, statusCode, body, headers = {}) {
  res.writeHead(statusCode, {
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end(body);
}

function renderAdminPage(state) {
  const hostedActive = state.activeBackend === BACKEND_HOSTED;
  const nuvioActive = state.activeBackend === BACKEND_NUVIO;

  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Nuvio Sync Switch</title>
    <style>
      :root {
        color-scheme: light dark;
        font-family:
          Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        line-height: 1.5;
      }

      body {
        margin: 0;
        min-height: 100vh;
        background: #f5f7fb;
        color: #172033;
      }

      main {
        width: min(720px, calc(100% - 32px));
        margin: 0 auto;
        padding: 48px 0;
      }

      h1 {
        margin: 0 0 8px;
        font-size: clamp(2rem, 4vw, 3rem);
        line-height: 1.1;
        letter-spacing: 0;
      }

      .status {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 12px;
        margin: 28px 0;
      }

      .metric,
      .actions {
        border: 1px solid #d8deea;
        border-radius: 8px;
        background: #ffffff;
      }

      .metric {
        padding: 16px;
      }

      .label {
        margin: 0 0 4px;
        color: #5d6b82;
        font-size: 0.8rem;
        text-transform: uppercase;
      }

      .value {
        margin: 0;
        font-size: 1.2rem;
        font-weight: 700;
        overflow-wrap: anywhere;
      }

      .actions {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 16px;
        padding: 16px;
      }

      form {
        margin: 0;
      }

      button {
        width: 100%;
        min-height: 52px;
        border: 0;
        border-radius: 8px;
        background: #1f6feb;
        color: #ffffff;
        cursor: pointer;
        font: inherit;
        font-weight: 700;
      }

      button:disabled {
        background: #8c98aa;
        cursor: default;
      }

      .hint {
        color: #5d6b82;
        margin: 8px 0 0;
      }

      @media (max-width: 640px) {
        main {
          padding: 28px 0;
        }

        .status,
        .actions {
          grid-template-columns: 1fr;
        }
      }

      @media (prefers-color-scheme: dark) {
        body {
          background: #101622;
          color: #edf2fb;
        }

        .metric,
        .actions {
          background: #172033;
          border-color: #303b4d;
        }

        .label,
        .hint {
          color: #a9b6ca;
        }
      }
    </style>
  </head>
  <body>
    <main>
      <h1>Nuvio Sync Switch</h1>
      <p class="hint">Select the Supabase backend exposed by <code>/config.json</code>.</p>
      <section class="status" aria-label="Current status">
        <div class="metric">
          <p class="label">Active backend</p>
          <p class="value">${escapeHtml(state.activeBackend)}</p>
        </div>
        <div class="metric">
          <p class="label">Revision</p>
          <p class="value">${escapeHtml(String(state.revision))}</p>
        </div>
        <div class="metric">
          <p class="label">Force logout</p>
          <p class="value">${serverConfig.forceLogoutOnChange ? 'true' : 'false'}</p>
        </div>
      </section>
      <section class="actions" aria-label="Backend switch actions">
        <form method="post" action="/admin/backend">
          <input type="hidden" name="activeBackend" value="hosted">
          <button type="submit" ${hostedActive ? 'disabled' : ''} data-target="hosted">Switch to Hosted</button>
        </form>
        <form method="post" action="/admin/backend">
          <input type="hidden" name="activeBackend" value="nuvio">
          <button type="submit" ${nuvioActive ? 'disabled' : ''} data-target="nuvio">Switch to Nuvio</button>
        </form>
      </section>
    </main>
    <script>
      for (const form of document.querySelectorAll('form')) {
        form.addEventListener('submit', (event) => {
          const target = form.querySelector('input[name="activeBackend"]').value;
          if (!window.confirm('Switch Nuvio sync backend to ' + target + '? Active app sessions will be signed out once.')) {
            event.preventDefault();
          }
        });
      }
    </script>
  </body>
</html>`;
}

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (char) => {
    switch (char) {
      case '&':
        return '&amp;';
      case '<':
        return '&lt;';
      case '>':
        return '&gt;';
      case '"':
        return '&quot;';
      case "'":
        return '&#39;';
      default:
        return char;
    }
  });
}
