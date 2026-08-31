const contextPath = window.location.pathname.match(/^\/[^/]+/)?.[0] || '';
const api = `${window.location.origin}${contextPath}/api`;
let authMode = 'login';
let currentUser;
let selectedMovie;
let selectedShow;
let selectedSeats = [];
let selectedTheatre;
let selectedScreen;
let movies = [];
let seatRefreshTimer;

const $ = (selector) => document.querySelector(selector);
const notice = $('#notice');

function showNotice(message, isError = false) {
    notice.textContent = message;
    notice.classList.remove('hidden');
    notice.style.background = isError ? '#e2684b' : '#e9bd55';
    notice.style.color = isError ? '#fffdf8' : '#172126';
}

async function request(path, options = {}) {
    const response = await fetch(api + path, {
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
        ...options
    });
    const text = await response.text();
    let body = {};
    try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { error: text }; }
    if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
    return body;
}

function setLoggedIn(user) {
    currentUser = user;
    $('#auth-panel').classList.add('hidden');
    $('#wallet-button').classList.remove('hidden');
    $('#logout-button').classList.remove('hidden');
    $('#user-label').textContent = `${user.name} / ${user.role}`;
    if (user.role === 'ADMIN') {
        $('#admin-panel').classList.remove('hidden');
        $('#bookings-section').classList.add('hidden');
        renderAccountPanel(user);
        loadAdminMovies();
        loadTheatres();
    } else {
        $('#admin-panel').classList.add('hidden');
        $('#bookings-section').classList.remove('hidden');
        renderAccountPanel(user);
        loadBookings();
    }
}

function setLoggedOut() {
    currentUser = undefined;
    $('#auth-panel').classList.remove('hidden');
    $('#admin-panel').classList.add('hidden');
    $('#bookings-section').classList.add('hidden');
    $('#account-panel').classList.add('hidden');
    $('#wallet-button').classList.add('hidden');
    $('#logout-button').classList.add('hidden');
    $('#user-label').textContent = 'Browsing as guest';
    selectedTheatre = undefined;
    selectedScreen = undefined;
    stopSeatRefresh();
}

function renderAccountPanel(user) {
    $('#account-kind').textContent = user.role === 'ADMIN' ? 'Admin account' : 'Customer account';
    $('#account-name').textContent = user.name;
    $('#account-email').textContent = user.email;
    $('#account-role').textContent = user.role;
    $('#wallet-balance').textContent = `Rs ${Number(user.walletBalance).toLocaleString('en-IN')}`;
    $('#wallet-help').textContent = user.role === 'ADMIN'
        ? 'Booking payments are credited here; refresh after customer cancellations.'
        : 'Balance refreshes after every booking and cancellation.';
}

async function refreshCurrentUser() {
    const user = await request('/auth/me');
    currentUser = user;
    renderAccountPanel(user);
    $('#user-label').textContent = `${user.name} / ${user.role}`;
}

async function loadMovies() {
    try {
        movies = await request('/movies');
        $('#movie-grid').innerHTML = movies.map((movie) => `
            <article class="movie-card">
                <div><div class="poster">${escapeHtml(movie.movieName)}</div>
                <h3>${escapeHtml(movie.movieName)}</h3>
                <p class="movie-meta">${escapeHtml(movie.certification)} &middot; ${movie.durationInMinutes} min</p></div>
                <button class="card-action" data-movie-id="${movie.movieId}" type="button">View details &#8594;</button>
            </article>`).join('');
        document.querySelectorAll('[data-movie-id]').forEach((button) => button.addEventListener('click', () => showMovie(button.dataset.movieId)));
    } catch (error) { showNotice(error.message, true); }
}

async function showMovie(movieId) {
    try {
        selectedMovie = await request(`/movies/${movieId}`);
        const shows = await request(`/shows?movieId=${movieId}`);
        $('#details-panel').classList.remove('hidden');
        $('#movie-details').innerHTML = `
            <p class="kicker">Movie details</p><h2>${escapeHtml(selectedMovie.movieName)}</h2>
            <p class="details-copy">${escapeHtml(selectedMovie.description || 'A story waiting for its audience.')}</p>
            <div class="show-list">${shows.length ? shows.map(showTemplate).join('') : '<p class="details-copy">No upcoming shows are scheduled yet.</p>'}</div>`;
        document.querySelectorAll('[data-show-id]').forEach((button) => button.addEventListener('click', () => showSeats(button.dataset.showId)));
        $('#details-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) { showNotice(error.message, true); }
}

function showTemplate(show) {
    const action = currentUser?.role === 'CUSTOMER'
        ? `<button class="button button-quiet" data-show-id="${show.showId}" type="button">Choose seats</button>`
        : '';
    return `<div class="show-row"><span class="show-time">${formatShowTime(show)}</span>${action}</div>`;
}

async function showSeats(showId) {
    try {
        selectedShow = { showId };
        selectedSeats = [];
        await renderSeats(showId, true);
        startSeatRefresh(showId);
    } catch (error) { showNotice(error.message, true); }
}

async function renderSeats(showId, resetSelection = false) {
    const seats = await request(`/shows/${showId}/seats`);
    if (resetSelection) selectedSeats = [];
    const seatMarkup = seats.map((seat) => {
        const key = `${seat.rowLabel}-${seat.seatNumber}`;
        const selected = selectedSeats.includes(key);
        return `<button class="seat ${seat.available ? 'available' : 'unavailable'} ${selected ? 'selected' : ''}" data-seat="${key}" ${seat.available ? '' : 'disabled'} type="button">${seat.rowLabel}${seat.seatNumber}</button>`;
    }).join('');
    const list = $('.show-list');
    if (!list) return;
    const selectedLabel = selectedSeats.length ? selectedSeats.join(', ') : 'none';
    list.innerHTML = `<div class="show-row"><div><p class="kicker">Select seats</p><div class="seat-list">${seatMarkup}</div><p class="details-copy">Selected seats: <span id="selected-seat-label">${selectedLabel}</span></p></div><button id="book-button" class="button button-primary" type="button">Book selected seats</button></div>`;
    document.querySelectorAll('.seat.available').forEach((button) => button.addEventListener('click', () => toggleSeat(button)));
    $('#book-button').addEventListener('click', bookSelectedSeats);
}

function startSeatRefresh(showId) {
    clearInterval(seatRefreshTimer);
    seatRefreshTimer = setInterval(async () => {
        if (selectedShow?.showId !== showId || !$('.show-list')) return;
        try { await renderSeats(showId); } catch (error) { showNotice(error.message, true); }
    }, 10000);
}

function stopSeatRefresh() {
    clearInterval(seatRefreshTimer);
    seatRefreshTimer = undefined;
}

/*
 * The show API returns a local date-time. Parse it as local time and calculate
 * the end from the selected movie duration instead of relying on Date parsing.
 */
function formatShowTime(show) {
    const start = parseLocalDateTime(show.showTiming);
    const duration = movieDuration(show.movieId);
    const end = new Date(start.getTime() + duration * 60000);
    return `${formatDate(start)} - ${formatTime(end)}`;
}

function movieDuration(movieId) {
    return movies.find((movie) => movie.movieId === movieId)?.durationInMinutes
        || (selectedMovie?.movieId === movieId ? selectedMovie.durationInMinutes : 0);
}

function parseLocalDateTime(value) {
    if (value instanceof Date) return value;
    const normalized = String(value || '').replace(' ', 'T');
    const parsed = new Date(normalized);
    return Number.isNaN(parsed.getTime()) ? new Date(0) : parsed;
}

function formatDate(value) {
    const date = parseLocalDateTime(value);
    return date.toLocaleDateString([], { dateStyle: 'medium' });
}

function formatTime(value) {
    return parseLocalDateTime(value).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

function toggleSeat(button) {
    const seat = button.dataset.seat;
    selectedSeats = selectedSeats.includes(seat) ? selectedSeats.filter((value) => value !== seat) : [...selectedSeats, seat];
    button.classList.toggle('selected');
    $('#selected-seat-label').textContent = selectedSeats.length ? selectedSeats.join(', ') : 'none';
}

async function bookSelectedSeats() {
    if (!selectedSeats.length) { showNotice('Choose at least one seat first.', true); return; }
    try {
        const seats = selectedSeats.map((seat) => ({ rowLabel: seat.split('-')[0], seatNumber: Number(seat.split('-')[1]) }));
        const booking = await request('/bookings', { method: 'POST', body: JSON.stringify({ showId: selectedShow.showId, seats }) });
        showNotice(`Booking #${booking.bookingId} confirmed.`);
        await refreshCurrentUser();
        await showSeats(selectedShow.showId);
        await loadBookings();
    } catch (error) { showNotice(error.message, true); }
}

async function loadBookings() {
    try {
        const bookings = await request('/bookings');
        $('#booking-list').innerHTML = bookings.length ? bookings.map((booking) => `<div class="booking-item"><div><strong>#${booking.bookingId}</strong><div>${booking.status} &middot; ${booking.seats.map((seat) => seat.rowLabel + seat.seatNumber).join(', ')}</div></div><button class="button button-quiet" data-cancel-id="${booking.bookingId}" type="button" ${booking.status !== 'CONFIRMED' ? 'disabled' : ''}>Cancel</button></div>`).join('') : '<p class="loading">No bookings yet.</p>';
        document.querySelectorAll('[data-cancel-id]').forEach((button) => button.addEventListener('click', () => cancelBooking(button.dataset.cancelId)));
    } catch (_) { $('#booking-list').innerHTML = '<p class="loading">Log in as a customer to see bookings.</p>'; }
}

async function loadAdminMovies() {
    try {
        movies = await request('/movies');
        $('#show-movie').innerHTML = movies.map((movie) => `<option value="${movie.movieId}">${escapeHtml(movie.movieName)} (${movie.ticketPrice ?? ''})</option>`).join('');
    } catch (error) { showNotice(error.message, true); }
}

async function loadTheatres() {
    try {
        const theatres = await request('/theatres');
        $('#theatre-list').innerHTML = theatres.length ? theatres.map((theatre) => `
            <button class="admin-item ${selectedTheatre?.theatreId === theatre.theatreId ? 'selected' : ''}" data-theatre-id="${theatre.theatreId}" type="button">
                <span><strong>${escapeHtml(theatre.theatreName)}</strong><small>${escapeHtml(theatre.theatreLocation)}</small></span><span class="item-actions"><i data-edit-theatre="${theatre.theatreId}" title="Edit theatre">&#9998;</i><i data-delete-theatre="${theatre.theatreId}" title="Delete theatre">&#215;</i></span>
            </button>`).join('') : '<p class="admin-context">No theatres yet. Create one to begin.</p>';
        document.querySelectorAll('[data-theatre-id]').forEach((item) => item.addEventListener('click', (event) => {
            if (event.target.dataset.editTheatre || event.target.dataset.deleteTheatre) return;
            selectTheatre(theatres.find((theatre) => theatre.theatreId === Number(item.dataset.theatreId)));
        }));
        document.querySelectorAll('[data-edit-theatre]').forEach((item) => item.addEventListener('click', (event) => { event.stopPropagation(); editTheatre(theatres.find((theatre) => theatre.theatreId === Number(item.dataset.editTheatre))); }));
        document.querySelectorAll('[data-delete-theatre]').forEach((item) => item.addEventListener('click', (event) => { event.stopPropagation(); deleteTheatre(item.dataset.deleteTheatre); }));
    } catch (error) { showNotice(error.message, true); }
}

async function selectTheatre(theatre) {
    selectedTheatre = theatre;
    selectedScreen = undefined;
    $('#screen-context').textContent = `${theatre.theatreName} / ${theatre.theatreLocation}`;
    $('#new-screen-button').disabled = false;
    $('#show-context').textContent = 'Select a screen first.';
    $('#new-show-button').disabled = true;
    hideForm('screen-form');
    hideForm('show-form');
    try {
        const screens = await request(`/screens/theatre/${theatre.theatreId}`);
        $('#screen-list').innerHTML = screens.length ? screens.map((screen) => `<button class="admin-item ${selectedScreen?.screenId === screen.screenId ? 'selected' : ''}" data-screen-id="${screen.screenId}" type="button"><span><strong>${escapeHtml(screen.screenName)}</strong><small>Rows ${escapeHtml(screen.rowRange)} / ${screen.seatsPerRow} seats</small></span><span class="item-actions"><i data-edit-screen="${screen.screenId}" title="Edit screen">&#9998;</i><i data-delete-screen="${screen.screenId}" title="Delete screen">&#215;</i></span></button>`).join('') : '<p class="admin-context">No screens yet.</p>';
        $('#screen-list').onclick = (event) => {
            const action = event.target.closest('[data-edit-screen], [data-delete-screen]');
            if (action) return;
            const item = event.target.closest('[data-screen-id]');
            if (item) selectScreen(screens.find((screen) => screen.screenId === Number(item.dataset.screenId)));
        };
        document.querySelectorAll('[data-edit-screen]').forEach((item) => item.addEventListener('click', (event) => { event.stopPropagation(); editScreen(screens.find((screen) => screen.screenId === Number(item.dataset.editScreen))); }));
        document.querySelectorAll('[data-delete-screen]').forEach((item) => item.addEventListener('click', (event) => { event.stopPropagation(); deleteScreen(item.dataset.deleteScreen); }));
    } catch (error) { showNotice(error.message, true); }
}

async function selectScreen(screen) {
    selectedScreen = screen;
    $('#show-context').textContent = `${selectedTheatre.theatreName} / ${screen.screenName}`;
    $('#new-show-button').disabled = false;
    hideForm('show-form');
    try {
        const shows = await request(`/screens/${screen.screenId}/shows`);
        $('#show-list').innerHTML = shows.length ? shows.map((show) => `<div class="admin-item"><span><strong>${escapeHtml(movieName(show.movieId))}</strong><small>${formatShowTime(show)}</small></span><span class="item-actions"><i data-edit-show="${show.showId}" title="Edit show">&#9998;</i><i data-delete-show="${show.showId}" title="Delete show">&#215;</i></span></div>`).join('') : '<p class="admin-context">No shows yet.</p>';
        document.querySelectorAll('[data-edit-show]').forEach((item) => item.addEventListener('click', () => editShow(shows.find((show) => show.showId === Number(item.dataset.editShow)))));
        document.querySelectorAll('[data-delete-show]').forEach((item) => item.addEventListener('click', () => deleteShow(item.dataset.deleteShow)));
    } catch (error) { showNotice(error.message, true); }
}

function movieName(movieId) { return movies.find((movie) => movie.movieId === movieId)?.movieName || `Movie #${movieId}`; }
function editTheatre(theatre) { $('#theatre-id').value = theatre.theatreId; $('#theatre-name').value = theatre.theatreName; $('#theatre-location').value = theatre.theatreLocation; showForm('theatre-form'); }
function editScreen(screen) { $('#screen-id').value = screen.screenId; $('#screen-name').value = screen.screenName; $('#screen-rows').value = screen.rowRange; $('#screen-seats').value = screen.seatsPerRow; showForm('screen-form'); }
function editShow(show) { $('#show-id').value = show.showId; $('#show-movie').value = show.movieId; $('#show-time').value = show.showTiming.slice(0, 16); showForm('show-form'); }
function showForm(id) { $(`#${id}`).classList.remove('hidden'); }
function hideForm(id) { $(`#${id}`).classList.add('hidden'); }
function resetForm(id) { document.querySelector(`#${id}`).reset(); document.querySelector(`#${id} input[type="hidden"]`).value = ''; hideForm(id); }

async function saveTheatre(event) { event.preventDefault(); const id = $('#theatre-id').value; const body = { theatreName: $('#theatre-name').value, theatreLocation: $('#theatre-location').value }; try { await request(id ? `/theatres/${id}` : '/theatres', { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) }); resetForm('theatre-form'); showNotice(id ? 'Theatre updated.' : 'Theatre created.'); await loadTheatres(); } catch (error) { showNotice(error.message, true); } }
async function saveScreen(event) { event.preventDefault(); if (!selectedTheatre) return; const id = $('#screen-id').value; const body = { theatreId: selectedTheatre.theatreId, screenName: $('#screen-name').value, rowRange: $('#screen-rows').value, seatsPerRow: Number($('#screen-seats').value) }; try { await request(id ? `/screens/${id}` : '/screens', { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) }); resetForm('screen-form'); showNotice(id ? 'Screen updated.' : 'Screen created.'); await selectTheatre(selectedTheatre); } catch (error) { showNotice(error.message, true); } }
async function saveShow(event) { event.preventDefault(); if (!selectedScreen) return; const id = $('#show-id').value; const body = { movieId: Number($('#show-movie').value), screenId: selectedScreen.screenId, showTiming: $('#show-time').value }; try { await request(id ? `/shows/${id}` : '/shows', { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) }); resetForm('show-form'); showNotice(id ? 'Show updated.' : 'Show created.'); await selectScreen(selectedScreen); } catch (error) { showNotice(error.message, true); } }
async function deleteTheatre(id) { if (!confirm('Delete this theatre? Its screens and shows must already be removed.')) return; try { await request(`/theatres/${id}`, { method: 'DELETE' }); showNotice('Theatre deleted.'); selectedTheatre = undefined; await loadTheatres(); } catch (error) { showNotice(error.message, true); } }
async function deleteScreen(id) { if (!confirm('Delete this screen? Its shows must already be removed.')) return; try { await request(`/screens/${id}`, { method: 'DELETE' }); showNotice('Screen deleted.'); await selectTheatre(selectedTheatre); } catch (error) { showNotice(error.message, true); } }
async function deleteShow(id) { if (!confirm('Delete this show?')) return; try { await request(`/shows/${id}`, { method: 'DELETE' }); showNotice('Show deleted.'); await selectScreen(selectedScreen); } catch (error) { showNotice(error.message, true); } }

async function cancelBooking(bookingId) {
    try { await request(`/bookings/${bookingId}/cancel`, { method: 'POST', body: '{}' }); await refreshCurrentUser(); if (selectedShow) await renderSeats(selectedShow.showId); showNotice(`Booking #${bookingId} cancelled and refunded according to policy.`); await loadBookings(); }
    catch (error) { showNotice(error.message, true); }
}

document.querySelectorAll('[data-auth-mode]').forEach((tab) => tab.addEventListener('click', () => {
    authMode = tab.dataset.authMode;
    document.querySelectorAll('[data-auth-mode]').forEach((item) => item.classList.toggle('active', item === tab));
    $('#name-field').classList.toggle('hidden', authMode === 'login');
    $('#auth-submit-label').textContent = authMode === 'login' ? 'Log in' : 'Create account';
    $('#password').autocomplete = authMode === 'login' ? 'current-password' : 'new-password';
}));

$('#logout-button').addEventListener('click', async () => { try { await request('/auth/logout', { method: 'POST', body: '{}' }); setLoggedOut(); showNotice('You are logged out.'); } catch (error) { showNotice(error.message, true); } });
$('#wallet-button').addEventListener('click', () => $('#account-panel').classList.toggle('hidden'));
$('#refresh-wallet-button').addEventListener('click', async () => {
    try { await refreshCurrentUser(); showNotice('Wallet balance refreshed.'); }
    catch (error) { showNotice(error.message, true); }
});
$('#refresh-button').addEventListener('click', async () => { await loadMovies(); if (selectedShow) { try { await renderSeats(selectedShow.showId); } catch (error) { showNotice(error.message, true); } } });
$('#close-details').addEventListener('click', () => { $('#details-panel').classList.add('hidden'); stopSeatRefresh(); });
$('#auth-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
        const email = $('#email').value;
        const password = $('#password').value;
        if (authMode === 'register') await request('/auth/register', { method: 'POST', body: JSON.stringify({ name: $('#name').value, email, password, role: 'CUSTOMER' }) });
        const user = await request('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
        setLoggedIn(user); showNotice(`Welcome, ${user.name}.`); event.target.reset();
    } catch (error) { showNotice(error.message, true); }
});
$('#theatre-form').addEventListener('submit', saveTheatre);
$('#screen-form').addEventListener('submit', saveScreen);
$('#show-form').addEventListener('submit', saveShow);
$('#new-theatre-button').addEventListener('click', () => { resetForm('theatre-form'); showForm('theatre-form'); });
$('#new-screen-button').addEventListener('click', () => { resetForm('screen-form'); showForm('screen-form'); });
$('#new-show-button').addEventListener('click', () => { resetForm('show-form'); showForm('show-form'); });
$('#cancel-theatre').addEventListener('click', () => hideForm('theatre-form'));
$('#cancel-screen').addEventListener('click', () => hideForm('screen-form'));
$('#cancel-show').addEventListener('click', () => hideForm('show-form'));

function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, (character) => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#039;' }[character])); }

loadMovies();
request('/auth/me').then(setLoggedIn).catch(() => setLoggedOut());
