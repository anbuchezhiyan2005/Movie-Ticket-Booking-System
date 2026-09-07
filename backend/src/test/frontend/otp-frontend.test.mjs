import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const app = await readFile(new URL('../../main/webapp/app.js', import.meta.url), 'utf8');
const html = await readFile(new URL('../../main/webapp/index.html', import.meta.url), 'utf8');

test('booking UI uses OTP request and verification endpoints', () => {
    assert.match(app, /request\('\/bookings\/otp\/request'/);
    assert.match(app, /`\/bookings\/\$\{challenge\.booking\.bookingId\}\/otp\/verify`/);
    assert.match(html, /id="booking-otp-panel" class="panel otp-panel hidden"/);
    assert.match(html, /id="booking-otp-expiry-timer"/);
});

test('cancellation UI uses OTP request and verification endpoints', () => {
    assert.match(app, /`\/bookings\/\$\{bookingId\}\/cancel\/otp\/request`/);
    assert.match(app, /`\/bookings\/\$\{bookingId\}\/cancel\/otp\/verify`/);
    assert.doesNotMatch(app, /`\/bookings\/\$\{bookingId\}\/cancel`/);
    assert.match(html, /id="cancellation-otp-panel" class="panel otp-panel hidden"/);
    assert.match(html, /id="otp-expiry-timer"/);
    assert.match(html, /id="otp-resend-button"/);
});