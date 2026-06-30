#!/usr/bin/env node
// Firebase Admin Auth — get OAuth2 access token from service account JSON
// Reads FIREBASE_SERVICE_ACCOUNT env var

const crypto = require('crypto');

const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);

const header = { alg: 'RS256', typ: 'JWT' };
const now = Math.floor(Date.now() / 1000);
const payload = {
  iss: sa.client_email,
  scope: 'https://www.googleapis.com/auth/firebase https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/datastore',
  aud: sa.token_uri,
  iat: now,
  exp: now + 3600,
};

const b64url = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
const jwtInput = b64url(header) + '.' + b64url(payload);

const sign = crypto.createSign('RSA-SHA256');
sign.update(jwtInput);
sign.end();
const signature = sign.sign(sa.private_key, 'base64url');

const assertion = jwtInput + '.' + signature;

fetch(sa.token_uri, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({
    grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
    assertion,
  }).toString(),
})
  .then(r => r.json())
  .then(d => {
    if (d.access_token) {
      process.stdout.write(d.access_token);
    } else {
      console.error('Failed:', JSON.stringify(d));
      process.exit(1);
    }
  })
  .catch(e => { console.error('Error:', e.message); process.exit(1); });
