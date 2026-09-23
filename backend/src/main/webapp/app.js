const contextPath = window.location.pathname.match(/^\/[^/]+/)?.[0] || '';
const api = `${window.location.origin}${contextPath}/api`;
let authMode = 'login';
let registrationRole = 'CUSTOMER';
let currentUser;
let csrfToken = '';
let selectedMovie;
let selectedShow;
let selectedSeats = [];
let selectedTheatre;
let selectedScreen;
let movies = [];
let seatRefreshTimer;
let cancellationOtp;
let cancellationOtpTimer;
let bookingOtp;
let bookingOtpTimer;
let pendingHoldSeats = new Set();

const $ = (selector) => document.querySelector(selector);
const notice = $('#notice');

function showNotice(message, isError = false) {
    notice.textContent = message;
    notice.classList.remove('hidden');
    notice.style.background = isError ? '#e2684b' : '#e9bd55';
    notice.style.color = isError ? '#fffdf8' : '#172126';
}

async function request(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const isStateChangingRequest = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method);
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (isStateChangingRequest && csrfToken) {
        headers['X-CSRF-Token'] = csrfToken;
    }

    const response = await fetch(api + path, {
        credentials: 'same-origin',
        headers,
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
    if (user?.csrfToken) {
        csrfToken = user.csrfToken;
    }
    $('#auth-panel').classList.add('hidden');
    $('#wallet-button').classList.remove('hidden');
    $('#logout-button').classList.remove('hidden');
    $('#provider-links').classList.toggle('hidden', user.role !== 'CUSTOMER');
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
    csrfToken = '';
    $('#auth-panel').classList.remove('hidden');
    $('#admin-panel').classList.add('hidden');
    $('#bookings-section').classList.add('hidden');
    $('#account-panel').classList.add('hidden');
    $('#wallet-button').classList.add('hidden');
    $('#logout-button').classList.add('hidden');
    $('#provider-links').classList.add('hidden');
    $('#user-label').textContent = 'Browsing as guest';
    selectedTheatre = undefined;
    selectedScreen = undefined;
    stopSeatRefresh();
    closeCancellationOtp();
    closeBookingOtp();
}

function parseServerTimestamp(value, fallback) {
    const parsed = typeof value === 'number' ? value : Date.parse(String(value || '').replace(' ', 'T'));
    return Number.isFinite(parsed) ? parsed : fallback;
}

function formatCountdown(milliseconds) {
    const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
    return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

function renderCancellationOtp() {
    const panel = $('#cancellation-otp-panel');
    panel.classList.remove('hidden');
    $('#otp-panel-destination').textContent = `A code was sent to ${cancellationOtp.destination} for booking #${cancellationOtp.bookingId}.`;
    $('#cancellation-otp-code').value = '';
    $('#otp-status').textContent = '';
    updateCancellationOtpTimers();
    $('#cancellation-otp-code').focus();
    panel.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function updateCancellationOtpTimers() {
    if (!cancellationOtp) return;
    const now = Date.now();
    const expiresIn = cancellationOtp.expiresAt - now;
    const resendIn = cancellationOtp.resendAvailableAt - now;
    $('#otp-expiry-timer').textContent = formatCountdown(expiresIn);
    $('#otp-resend-button').disabled = resendIn > 0 || cancellationOtp.busy || expiresIn <= 0;
    $('#otp-resend-timer').textContent = expiresIn <= 0
        ? 'This code has expired. Close this panel and start cancellation again.'
        : resendIn > 0 ? `You can resend in ${formatCountdown(resendIn)}.` : 'You can request a new code.';
    $('#otp-verify-button').disabled = cancellationOtp.busy || expiresIn <= 0;
    if (expiresIn <= 0) {
        clearInterval(cancellationOtpTimer);
        $('#otp-status').textContent = 'The verification window has expired.';
    }
}

function startCancellationOtpTimer() {
    clearInterval(cancellationOtpTimer);
    cancellationOtpTimer = setInterval(updateCancellationOtpTimers, 1000);
}

function closeCancellationOtp() {
    clearInterval(cancellationOtpTimer);
    cancellationOtpTimer = undefined;
    cancellationOtp = undefined;
    $('#cancellation-otp-panel')?.classList.add('hidden');
}

function storeCancellationChallenge(bookingId, challenge) {
    cancellationOtp = {
        bookingId,
        challengeToken: challenge.challengeToken,
        destination: challenge.destination,
        expiresAt: parseServerTimestamp(challenge.expiresAt, Date.now() + 5 * 60 * 1000),
        resendAvailableAt: parseServerTimestamp(challenge.resendAvailableAt, Date.now() + 60 * 1000),
        busy: false
    };
    renderCancellationOtp();
    startCancellationOtpTimer();
}

function renderBookingOtp() {
    const panel = $('#booking-otp-panel');
    panel.classList.remove('hidden');
    $('#booking-otp-destination').textContent = `A code was sent to ${bookingOtp.destination} for your booking.`;
    $('#booking-otp-code').value = '';
    $('#booking-otp-status').textContent = '';
    updateBookingOtpTimers();
    $('#booking-otp-code').focus();
    panel.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function updateBookingOtpTimers() {
    if (!bookingOtp) return;
    const now = Date.now();
    const expiresIn = bookingOtp.expiresAt - now;
    const resendIn = bookingOtp.resendAvailableAt - now;
    $('#booking-otp-expiry-timer').textContent = formatCountdown(expiresIn);
    $('#booking-otp-resend-button').disabled = resendIn > 0 || bookingOtp.busy || expiresIn <= 0;
    $('#booking-otp-resend-timer').textContent = expiresIn <= 0
        ? 'This code has expired. Close this panel and select the seats again.'
        : resendIn > 0 ? `You can resend in ${formatCountdown(resendIn)}.` : 'You can request a new code.';
    $('#booking-otp-verify-button').disabled = bookingOtp.busy || expiresIn <= 0;
    if (expiresIn <= 0) {
        clearInterval(bookingOtpTimer);
        $('#booking-otp-status').textContent = 'The verification window has expired.';
    }
}

function startBookingOtpTimer() {
    clearInterval(bookingOtpTimer);
    bookingOtpTimer = setInterval(updateBookingOtpTimers, 1000);
}

function closeBookingOtp() {
    clearInterval(bookingOtpTimer);
    bookingOtpTimer = undefined;
    bookingOtp = undefined;
    $('#booking-otp-panel')?.classList.add('hidden');
}

function storeBookingChallenge(challenge) {
    bookingOtp = {
        bookingId: challenge.booking.bookingId,
        challengeToken: challenge.challengeToken,
        destination: challenge.destination,
        expiresAt: parseServerTimestamp(challenge.expiresAt, Date.now() + 5 * 60 * 1000),
        resendAvailableAt: parseServerTimestamp(challenge.resendAvailableAt, Date.now() + 60 * 1000),
        booking: challenge.booking,
        busy: false
    };
    renderBookingOtp();
    startBookingOtpTimer();
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
        const grid = $('#movie-grid');
        grid.innerHTML = '';
        movies.forEach((movie) => {
            const article = document.createElement('article');
            article.className = 'movie-card';
            
            const poster = document.createElement('div');
            poster.className = 'poster';
            poster.textContent = movie.movieName;
            
            const title = document.createElement('h3');
            title.textContent = movie.movieName;
            
            const meta = document.createElement('p');
            meta.className = 'movie-meta';
            meta.textContent = `${movie.certification} \u00b7 ${movie.durationInMinutes} min`;
            
            const button = document.createElement('button');
            button.className = 'card-action';
            button.type = 'button';
            button.textContent = 'View details \u2192';
            button.dataset.movieId = movie.movieId;
            button.addEventListener('click', () => showMovie(button.dataset.movieId));
            
            const innerDiv = document.createElement('div');
            innerDiv.appendChild(poster);
            innerDiv.appendChild(title);
            innerDiv.appendChild(meta);
            
            article.appendChild(innerDiv);
            article.appendChild(button);
            grid.appendChild(article);
        });
    } catch (error) { showNotice(error.message, true); }
}

async function showMovie(movieId) {
    try {
        selectedMovie = await request(`/movies/${movieId}`);
        const shows = await request(`/shows?movieId=${movieId}`);
        $('#details-panel').classList.remove('hidden');
        
        const detailsPanel = $('#movie-details');
        detailsPanel.innerHTML = '';
        
        const kicker = document.createElement('p');
        kicker.className = 'kicker';
        kicker.textContent = 'Movie details';
        
        const title = document.createElement('h2');
        title.textContent = selectedMovie.movieName;
        
        const description = document.createElement('p');
        description.className = 'details-copy';
        description.textContent = selectedMovie.description || 'A story waiting for its audience.';
        
        const showList = document.createElement('div');
        showList.className = 'show-list';
        
        if (shows.length) {
            shows.forEach((show) => {
                const showRow = document.createElement('div');
                showRow.className = 'show-row';
                
                const showTime = document.createElement('span');
                showTime.className = 'show-time';
                showTime.textContent = formatShowTime(show);
                showRow.appendChild(showTime);
                
                if (currentUser?.role === 'CUSTOMER') {
                    const button = document.createElement('button');
                    button.className = 'button button-quiet';
                    button.type = 'button';
                    button.textContent = 'Choose seats';
                    button.dataset.showId = show.showId;
                    button.addEventListener('click', () => showSeats(button.dataset.showId));
                    showRow.appendChild(button);
                }
                showList.appendChild(showRow);
            });
        } else {
            const noShows = document.createElement('p');
            noShows.className = 'details-copy';
            noShows.textContent = 'No upcoming shows are scheduled yet.';
            showList.appendChild(noShows);
        }
        
        detailsPanel.appendChild(kicker);
        detailsPanel.appendChild(title);
        detailsPanel.appendChild(description);
        detailsPanel.appendChild(showList);
        
        $('#details-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) { showNotice(error.message, true); }
}

async function showSeats(showId) {
    try {
        selectedShow = { showId: Number(showId) };
        selectedSeats = [];
        await renderSeats(selectedShow.showId, true);
        startSeatRefresh(selectedShow.showId);
    } catch (error) { showNotice(error.message, true); }
}

async function renderSeats(showId, resetSelection = false) {
    const seatMap = await request(`/shows/${showId}/seats`);
    if (resetSelection) selectedSeats = [];

    const list = $('.show-list');
    if (!list) return;

    if (resetSelection || !list.querySelector('.seat-list')) {
        buildSeatGrid(list, showId, seatMap);
    }
    applyOccupiedSeats(seatMap.occupiedSeats || {});
}

function buildSeatGrid(list, showId, layout) {
    const showRow = document.createElement('div');
    showRow.className = 'show-row';

    const leftDiv = document.createElement('div');
    const kicker = document.createElement('p');
    kicker.className = 'kicker';
    kicker.textContent = `Select seats · ${layout.screenName}`;

    const seatListDiv = document.createElement('div');
    seatListDiv.className = 'seat-list';
    const rows = layout.rowRange.split('-');
    const firstRow = rows[0].trim().toUpperCase().charCodeAt(0);
    const lastRow = rows[1].trim().toUpperCase().charCodeAt(0);

    for (let rowCode = firstRow; rowCode <= lastRow; rowCode++) {
        const rowLabel = String.fromCharCode(rowCode);
        for (let seatNumber = 1; seatNumber <= layout.seatsPerRow; seatNumber++) {
            const key = `${rowLabel}-${seatNumber}`;
            const button = document.createElement('button');
            button.className = 'seat available';
            button.type = 'button';
            button.dataset.seat = key;
            button.textContent = `${rowLabel}${seatNumber}`;
            button.addEventListener('click', () => toggleSeat(button));
            seatListDiv.appendChild(button);
        }
    }

    const selectedLabel = document.createElement('p');
    selectedLabel.className = 'details-copy';
    const selectedSpan = document.createElement('span');
    selectedSpan.id = 'selected-seat-label';
    selectedSpan.textContent = 'none';
    selectedLabel.appendChild(document.createTextNode('Selected seats: '));
    selectedLabel.appendChild(selectedSpan);

    leftDiv.appendChild(kicker);
    leftDiv.appendChild(seatListDiv);
    leftDiv.appendChild(selectedLabel);

    const rightDiv = document.createElement('div');
    const bookButton = document.createElement('button');
    bookButton.id = 'book-button';
    bookButton.className = 'button button-primary';
    bookButton.type = 'button';
    bookButton.textContent = 'Book selected seats';
    bookButton.addEventListener('click', bookSelectedSeats);

    const refreshButton = document.createElement('button');
    refreshButton.id = 'refresh-seats-button';
    refreshButton.className = 'button button-quiet';
    refreshButton.type = 'button';
    refreshButton.textContent = '\u27F2 Refresh seats';
    refreshButton.addEventListener('click', () => renderSeats(showId));

    rightDiv.appendChild(bookButton);
    rightDiv.appendChild(refreshButton);
    showRow.appendChild(leftDiv);
    showRow.appendChild(rightDiv);
    list.innerHTML = '';
    list.appendChild(showRow);
}

function applyOccupiedSeats(occupiedSeats) {
    const removedSeats = [];
    const ownHeldSeats = new Set([
        ...pendingHoldSeats,
        ...(bookingOtp?.booking?.seats ?? []).map((s) => `${s.rowLabel}-${s.seatNumber}`)
    ]);
    document.querySelectorAll('.seat-list .seat').forEach((button) => {
        const key = button.dataset.seat;
        const status = String(occupiedSeats[key] || 'AVAILABLE').toUpperCase();
        const wasSelected = selectedSeats.includes(key);

        button.classList.remove('available', 'held', 'unavailable');
        if (status === 'BOOKED') {
            button.classList.add('unavailable');
            button.disabled = true;
        } else if (status === 'HELD') {
            button.classList.add('held');
            button.disabled = true;
        } else {
            button.classList.add('available');
            button.disabled = false;
        }

        button.classList.toggle('selected',
            (wasSelected && status === 'AVAILABLE') || ownHeldSeats.has(key));
        if (wasSelected && status !== 'AVAILABLE' && !ownHeldSeats.has(key)) removedSeats.push(key);
    });

    if (removedSeats.length) {
        selectedSeats = selectedSeats.filter((seat) => !removedSeats.includes(seat));
        showNotice(`Seat ${removedSeats.join(', ')} is no longer available`, true);
    }
    $('#selected-seat-label').textContent = selectedSeats.length ? selectedSeats.join(', ') : 'none';
}

function startSeatRefresh(showId) {
    clearTimeout(seatRefreshTimer);

    // Refresh the shared server-side state so changes by other customers appear promptly.
    seatRefreshTimer = setInterval(() => {
        if (selectedShow?.showId === showId && $('.show-list')) renderSeats(showId);
    }, 5000);
}

function stopSeatRefresh() {
    if (seatRefreshTimer) clearInterval(seatRefreshTimer);
    seatRefreshTimer = undefined;
}

/*
 * The show API returns a local date-time. Parse it as local time and calculate
 * the end from the selected movie duration instead of relying on Date parsing.
 */
function formatShowTime(show) {
    const start = parseLocalDateTime(show.showTiming);
    return `${formatDate(start)} - ${formatTime(start)}`;
}

function movieDuration(movieId) {
    return movies.find((movie) => movie.movieId === movieId)?.durationInMinutes
        || (selectedMovie?.movieId === movieId ? selectedMovie.durationInMinutes : 0);
}

function parseLocalDateTime(value) {
    if (value instanceof Date) return value;
    if (Array.isArray(value)) {
        const [year, month, day, hour = 0, minute = 0, second = 0] = value;
        const parsed = new Date(year, month - 1, day, hour, minute, second);
        return Number.isNaN(parsed.getTime()) ? new Date(0) : parsed;
    }
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

function formatBookingShowWindow(booking) {
    const start = parseLocalDateTime(booking.showStartTime);
    const end = new Date(start.getTime() + Number(booking.durationMinutes || 0) * 60 * 1000);
    return `${formatDate(start)} · ${formatTime(start)} - ${formatTime(end)}`;
}

function appendBookingDetail(container, label, value) {
    const row = document.createElement('div');
    row.className = 'booking-detail-row';

    const labelElement = document.createElement('span');
    labelElement.className = 'booking-detail-label';
    labelElement.textContent = label;

    const valueElement = document.createElement('strong');
    valueElement.className = 'booking-detail-value';
    valueElement.textContent = value;

    row.appendChild(labelElement);
    row.appendChild(valueElement);
    container.appendChild(row);
}

function renderBookingDetails(booking) {
    const panel = document.createElement('div');
    panel.className = 'booking-details hidden';

    const grid = document.createElement('div');
    grid.className = 'booking-detail-grid';
    appendBookingDetail(grid, 'Movie', booking.movieName || 'Movie details unavailable');
    appendBookingDetail(grid, 'Show', booking.showStartTime
        ? formatBookingShowWindow(booking)
        : 'Show details unavailable');
    appendBookingDetail(grid, 'Screen', booking.screenName || 'Screen details unavailable');
    appendBookingDetail(grid, 'Theatre', booking.theatreName || 'Theatre details unavailable');
    appendBookingDetail(grid, 'Location', booking.theatreLocation || 'Location unavailable');
    appendBookingDetail(grid, 'Seats', booking.seats.map((seat) => `${seat.rowLabel}${seat.seatNumber}`).join(', ') || 'none');
    appendBookingDetail(grid, 'Total paid', `Rs ${Number(booking.totalAmount).toLocaleString('en-IN')}`);
    appendBookingDetail(grid, 'Booked at', booking.bookingTime
        ? `${formatDate(booking.bookingTime)} · ${formatTime(booking.bookingTime)}`
        : 'Unavailable');

    panel.appendChild(grid);
    return panel;
}

function toggleSeat(button) {
    const seat = button.dataset.seat;
    selectedSeats = selectedSeats.includes(seat) ? selectedSeats.filter((value) => value !== seat) : [...selectedSeats, seat];
    button.classList.toggle('selected');
    $('#selected-seat-label').textContent = selectedSeats.length ? selectedSeats.join(', ') : 'none';
}

async function bookSelectedSeats() {
    if (!selectedSeats.length) {
        showNotice('Please select at least one seat', true);
        return;
    }
    if (!currentUser) {
        showNotice('Please log in first', true);
        return;
    }

    pendingHoldSeats = new Set(selectedSeats);
    try {
        const bookingRequest = {
            showId: selectedShow.showId,
            seats: selectedSeats.map(key => {
                const [row, num] = key.split('-');
                return { rowLabel: row, seatNumber: parseInt(num) };
            })
        };

        const challenge = await request('/bookings/otp/request', {
            method: 'POST',
            body: JSON.stringify(bookingRequest)
        });
        pendingHoldSeats.clear();
        storeBookingChallenge(challenge);
    } catch (error) {
        pendingHoldSeats.clear();
        showNotice(error.message, true);
    }
}

async function submitBookingOtp(event) {
    event.preventDefault();
    if (!bookingOtp) return;
    const code = $('#booking-otp-code').value.trim();
    if (!/^\d{6}$/.test(code)) {
        $('#booking-otp-status').textContent = 'Enter the six-digit verification code.';
        return;
    }
    bookingOtp.busy = true;
    updateBookingOtpTimers();
    try {
        const response = await request(`/bookings/${bookingOtp.bookingId}/otp/verify`, {
            method: 'POST',
            body: JSON.stringify({ challengeToken: bookingOtp.challengeToken, code })
        });
        closeBookingOtp();
        showNotice(`Booking confirmed! Booking ID: ${response.bookingId}`, false);
        await renderSeats(selectedShow.showId, true);
        await refreshCurrentUser();
        stopSeatRefresh();
        await loadBookings();
        $('#details-panel').classList.add('hidden');
    } catch (error) {
        if (bookingOtp) {
            bookingOtp.busy = false;
            $('#booking-otp-status').textContent = error.message;
            updateBookingOtpTimers();
        }
    }
}

async function resendBookingOtp() {
    if (!bookingOtp || bookingOtp.busy) return;
    bookingOtp.busy = true;
    updateBookingOtpTimers();
    try {
        const challenge = await request(`/bookings/${bookingOtp.bookingId}/otp/resend`, {
            method: 'POST', body: '{}'
        });
        storeBookingChallenge(challenge);
    } catch (error) {
        bookingOtp.busy = false;
        $('#booking-otp-status').textContent = error.message;
        updateBookingOtpTimers();
    }
}

async function loadBookings() {
    try {
        const bookings = await request('/bookings');
        const bookingList = $('#booking-list');
        bookingList.innerHTML = '';
        
        if (bookings.length) {
            bookings.forEach((booking) => {
                const item = document.createElement('div');
                item.className = 'booking-item';
                
                const details = document.createElement('div');
                const id = document.createElement('strong');
                id.textContent = `#${booking.bookingId}`;
                
                const statusDiv = document.createElement('div');
                const seatLabels = booking.seats.map((seat) => seat.rowLabel + seat.seatNumber).join(', ');
                statusDiv.textContent = `${booking.status} \u00b7 ${seatLabels}`;

                const detailPanel = renderBookingDetails(booking);
                const actions = document.createElement('div');
                actions.className = 'booking-actions';

                const viewButton = document.createElement('button');
                viewButton.className = 'button button-quiet';
                viewButton.type = 'button';
                viewButton.textContent = 'View details';
                viewButton.setAttribute('aria-expanded', 'false');
                viewButton.addEventListener('click', () => {
                    const expanded = !detailPanel.classList.toggle('hidden');
                    viewButton.textContent = expanded ? 'Hide details' : 'View details';
                    viewButton.setAttribute('aria-expanded', String(expanded));
                });
                
                details.appendChild(id);
                details.appendChild(statusDiv);
                actions.appendChild(viewButton);
                
                const button = document.createElement('button');
                button.className = 'button button-quiet';
                button.type = 'button';
                button.textContent = 'Cancel';
                button.dataset.cancelId = booking.bookingId;
                if (booking.status !== 'CONFIRMED') button.disabled = true;
                button.addEventListener('click', () => cancelBooking(button.dataset.cancelId));
                actions.appendChild(button);
                
                item.appendChild(details);
                item.appendChild(actions);
                item.appendChild(detailPanel);
                bookingList.appendChild(item);
            });
        } else {
            const noBookings = document.createElement('p');
            noBookings.className = 'loading';
            noBookings.textContent = 'No bookings yet.';
            bookingList.appendChild(noBookings);
        }
    } catch (_) {
        const bookingList = $('#booking-list');
        bookingList.innerHTML = '';
        const fallback = document.createElement('p');
        fallback.className = 'loading';
        fallback.textContent = 'Log in as a customer to see bookings.';
        bookingList.appendChild(fallback);
    }
}

async function loadAdminMovies() {
    try {
        movies = await request('/movies');
        const select = $('#show-movie');
        select.innerHTML = '';
        movies.forEach((movie) => {
            const option = document.createElement('option');
            option.value = movie.movieId;
            option.textContent = `${movie.movieName} (${movie.ticketPrice ?? ''})`;
            select.appendChild(option);
        });
    } catch (error) { showNotice(error.message, true); }
}

async function loadTheatres() {
    try {
        const theatres = await request('/theatres');
        const theatreList = $('#theatre-list');
        theatreList.innerHTML = '';
        
        if (theatres.length) {
            theatres.forEach((theatre) => {
                const button = document.createElement('button');
                button.className = `admin-item ${selectedTheatre?.theatreId === theatre.theatreId ? 'selected' : ''}`;
                button.type = 'button';
                button.dataset.theatreId = theatre.theatreId;
                
                const span1 = document.createElement('span');
                const name = document.createElement('strong');
                name.textContent = theatre.theatreName;
                const location = document.createElement('small');
                location.textContent = theatre.theatreLocation;
                span1.appendChild(name);
                span1.appendChild(location);
                
                const span2 = document.createElement('span');
                span2.className = 'item-actions';
                const editIcon = document.createElement('i');
                editIcon.dataset.editTheatre = theatre.theatreId;
                editIcon.title = 'Edit theatre';
                editIcon.textContent = '\u270E';
                const deleteIcon = document.createElement('i');
                deleteIcon.dataset.deleteTheatre = theatre.theatreId;
                deleteIcon.title = 'Delete theatre';
                deleteIcon.textContent = '\u00D7';
                span2.appendChild(editIcon);
                span2.appendChild(deleteIcon);
                
                button.appendChild(span1);
                button.appendChild(span2);
                
                button.addEventListener('click', (event) => {
                    if (event.target.dataset.editTheatre || event.target.dataset.deleteTheatre) return;
                    selectTheatre(theatres.find((t) => t.theatreId === Number(button.dataset.theatreId)));
                });
                editIcon.addEventListener('click', (event) => {
                    event.stopPropagation();
                    editTheatre(theatre);
                });
                deleteIcon.addEventListener('click', (event) => {
                    event.stopPropagation();
                    deleteTheatre(theatre.theatreId);
                });
                
                theatreList.appendChild(button);
            });
        } else {
            const noTheatres = document.createElement('p');
            noTheatres.className = 'admin-context';
            noTheatres.textContent = 'No theatres yet. Create one to begin.';
            theatreList.appendChild(noTheatres);
        }
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
        const screenList = $('#screen-list');
        screenList.innerHTML = '';
        
        if (screens.length) {
            screens.forEach((screen) => {
                const button = document.createElement('button');
                button.className = `admin-item ${selectedScreen?.screenId === screen.screenId ? 'selected' : ''}`;
                button.type = 'button';
                button.dataset.screenId = screen.screenId;
                
                const span1 = document.createElement('span');
                const name = document.createElement('strong');
                name.textContent = screen.screenName;
                const info = document.createElement('small');
                info.textContent = `Rows ${screen.rowRange} / ${screen.seatsPerRow} seats`;
                span1.appendChild(name);
                span1.appendChild(info);
                
                const span2 = document.createElement('span');
                span2.className = 'item-actions';
                const editIcon = document.createElement('i');
                editIcon.dataset.editScreen = screen.screenId;
                editIcon.title = 'Edit screen';
                editIcon.textContent = '\u270E';
                const deleteIcon = document.createElement('i');
                deleteIcon.dataset.deleteScreen = screen.screenId;
                deleteIcon.title = 'Delete screen';
                deleteIcon.textContent = '\u00D7';
                span2.appendChild(editIcon);
                span2.appendChild(deleteIcon);
                
                button.appendChild(span1);
                button.appendChild(span2);
                
                button.addEventListener('click', (event) => {
                    if (event.target.dataset.editScreen || event.target.dataset.deleteScreen) return;
                    selectScreen(screens.find((s) => s.screenId === Number(button.dataset.screenId)));
                });
                editIcon.addEventListener('click', (event) => {
                    event.stopPropagation();
                    editScreen(screen);
                });
                deleteIcon.addEventListener('click', (event) => {
                    event.stopPropagation();
                    deleteScreen(screen.screenId);
                });
                
                screenList.appendChild(button);
            });
        } else {
            const noScreens = document.createElement('p');
            noScreens.className = 'admin-context';
            noScreens.textContent = 'No screens yet.';
            screenList.appendChild(noScreens);
        }
    } catch (error) { showNotice(error.message, true); }
}

async function selectScreen(screen) {
    selectedScreen = screen;
    $('#show-context').textContent = `${selectedTheatre.theatreName} / ${screen.screenName}`;
    $('#new-show-button').disabled = false;
    hideForm('show-form');
    try {
        const route = currentUser?.role === 'ADMIN'
            ? `/admin/screens/${screen.screenId}/shows`
            : `/screens/${screen.screenId}/shows`;
        const shows = await request(route);
        const showList = $('#show-list');
        showList.innerHTML = '';
        
        if (shows.length) {
            shows.forEach((show) => {
                const item = document.createElement('div');
                item.className = 'admin-item';
                
                const span = document.createElement('span');
                const movieTitle = document.createElement('strong');
                movieTitle.textContent = movieName(show.movieId);
                const showTime = document.createElement('small');
                showTime.textContent = formatShowTime(show);
                span.appendChild(movieTitle);
                span.appendChild(showTime);
                
                const actionSpan = document.createElement('span');
                actionSpan.className = 'item-actions';
                const editIcon = document.createElement('i');
                editIcon.dataset.editShow = show.showId;
                editIcon.title = 'Edit show';
                editIcon.textContent = '\u270E';
                editIcon.addEventListener('click', () => editShow(show));
                actionSpan.appendChild(editIcon);
                
                item.appendChild(span);
                item.appendChild(actionSpan);
                showList.appendChild(item);
            });
        } else {
            const noShows = document.createElement('p');
            noShows.className = 'admin-context';
            noShows.textContent = 'No upcoming or active shows.';
            showList.appendChild(noShows);
        }
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

async function cancelBooking(bookingId) {
    if (!confirm('Are you sure you want to cancel this booking?')) return;
    try {
        const challenge = await request(`/bookings/${bookingId}/cancel/otp/request`, {
            method: 'POST', body: '{}'
        });
        storeCancellationChallenge(bookingId, challenge);
    } catch (error) {
        showNotice(error.message, true);
    }
}

async function submitCancellationOtp(event) {
    event.preventDefault();
    if (!cancellationOtp) return;
    const code = $('#cancellation-otp-code').value.trim();
    if (!/^\d{6}$/.test(code)) {
        $('#otp-status').textContent = 'Enter the six-digit verification code.';
        return;
    }
    cancellationOtp.busy = true;
    updateCancellationOtpTimers();
    try {
        await request(`/bookings/${cancellationOtp.bookingId}/cancel/otp/verify`, {
            method: 'POST',
            body: JSON.stringify({ challengeToken: cancellationOtp.challengeToken, code })
        });
        const bookingId = cancellationOtp.bookingId;
        closeCancellationOtp();
        showNotice(`Booking #${bookingId} cancelled and refunded according to policy.`, false);
        if (selectedShow?.showId) await renderSeats(selectedShow.showId);
        await refreshCurrentUser();
        await loadBookings();
    } catch (error) {
        if (cancellationOtp) {
            cancellationOtp.busy = false;
            $('#otp-status').textContent = error.message;
            updateCancellationOtpTimers();
        }
    }
}

async function resendCancellationOtp() {
    if (!cancellationOtp || cancellationOtp.busy) return;
    cancellationOtp.busy = true;
    updateCancellationOtpTimers();
    try {
        const challenge = await request(`/bookings/${cancellationOtp.bookingId}/cancel/otp/resend`, {
            method: 'POST', body: '{}'
        });
        storeCancellationChallenge(cancellationOtp.bookingId, challenge);
    } catch (error) {
        cancellationOtp.busy = false;
        $('#otp-status').textContent = error.message;
        updateCancellationOtpTimers();
    }
}

document.querySelectorAll('[data-auth-mode]').forEach((tab) => tab.addEventListener('click', () => {
    authMode = tab.dataset.authMode;
    document.querySelectorAll('[data-auth-mode]').forEach((item) => item.classList.toggle('active', item === tab));
    $('#name-field').classList.toggle('hidden', authMode === 'login');
    $('#phone-field').classList.toggle('hidden', authMode === 'login');
    $('#registration-role').classList.toggle('hidden', authMode !== 'register');
    $('#admin-key-field').classList.toggle('hidden', authMode !== 'register' || registrationRole !== 'ADMIN');
    $('#social-auth').classList.toggle('hidden', authMode === 'register' && registrationRole === 'ADMIN');
    $('#admin-key').required = authMode === 'register' && registrationRole === 'ADMIN';
    $('#auth-submit-label').textContent = authMode === 'login' ? 'Log in' : 'Create account';
    $('#password').autocomplete = authMode === 'login' ? 'current-password' : 'new-password';
}));

document.querySelectorAll('[data-register-role]').forEach((tab) => tab.addEventListener('click', () => {
    registrationRole = tab.dataset.registerRole;
    document.querySelectorAll('[data-register-role]').forEach((item) => item.classList.toggle('active', item === tab));
    $('#admin-key-field').classList.toggle('hidden', registrationRole !== 'ADMIN');
    $('#social-auth').classList.toggle('hidden', registrationRole === 'ADMIN');
    $('#admin-key').required = registrationRole === 'ADMIN';
}));

$('#logout-button').addEventListener('click', async () => { try { await request('/auth/logout', { method: 'POST', body: '{}' }); setLoggedOut(); showNotice('You are logged out.'); } catch (error) { showNotice(error.message, true); } });
$('#wallet-button').addEventListener('click', () => $('#account-panel').classList.toggle('hidden'));
$('#refresh-wallet-button').addEventListener('click', async () => {
    try { await refreshCurrentUser(); showNotice('Wallet balance refreshed.'); }
    catch (error) { showNotice(error.message, true); }
});
$('#refresh-button').addEventListener('click', async () => { await loadMovies(); if (selectedShow) { try { await renderSeats(selectedShow.showId); } catch (error) { showNotice(error.message, true); } } });
$('#close-details').addEventListener('click', () => { $('#details-panel').classList.add('hidden'); stopSeatRefresh(); });
$('#booking-otp-form').addEventListener('submit', submitBookingOtp);
$('#booking-otp-resend-button').addEventListener('click', resendBookingOtp);
$('#booking-otp-close-button').addEventListener('click', closeBookingOtp);
$('#cancellation-otp-form').addEventListener('submit', submitCancellationOtp);
$('#otp-resend-button').addEventListener('click', resendCancellationOtp);
$('#otp-close-button').addEventListener('click', closeCancellationOtp);
$('#auth-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
        const email = $('#email').value;
        const password = $('#password').value;
        if (authMode === 'register') {
            const payload = { name: $('#name').value, email, phoneNumber: $('#phone-number').value, password, role: registrationRole };
            if (registrationRole === 'ADMIN') payload.adminKey = $('#admin-key').value;
            await request(registrationRole === 'ADMIN' ? '/auth/register-admin' : '/auth/register', {
                method: 'POST',
                body: JSON.stringify(payload)
            });
        }
        const user = await request('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
        setLoggedIn(user);
        showNotice(`Welcome, ${user.name}.`);
        event.target.reset();
    } catch (error) { showNotice(error.message, true); }
});
$('#google-sign-in').addEventListener('click', () => { window.location.href = `${api}/auth/google/start`; });
$('#twitter-sign-in').addEventListener('click', () => { window.location.href = `${api}/auth/twitter/start`; });
$('#link-google-button').addEventListener('click', () => { window.location.href = `${api}/auth/google/link/start`; });
$('#link-twitter-button').addEventListener('click', () => { window.location.href = `${api}/auth/twitter/link/start`; });
$('#theatre-form').addEventListener('submit', saveTheatre);
$('#screen-form').addEventListener('submit', saveScreen);
$('#show-form').addEventListener('submit', saveShow);
$('#new-theatre-button').addEventListener('click', () => { resetForm('theatre-form'); showForm('theatre-form'); });
$('#new-screen-button').addEventListener('click', () => { resetForm('screen-form'); showForm('screen-form'); });
$('#new-show-button').addEventListener('click', () => { resetForm('show-form'); showForm('show-form'); });
$('#cancel-theatre').addEventListener('click', () => hideForm('theatre-form'));
$('#cancel-screen').addEventListener('click', () => hideForm('screen-form'));
$('#cancel-show').addEventListener('click', () => hideForm('show-form'));

loadMovies();
request('/auth/me').then(setLoggedIn).catch(() => setLoggedOut());

const oauthError = new URLSearchParams(window.location.search).get('oauth_error');
if (oauthError === 'account_conflict') {
    showNotice('That provider is linked to another account. Log in first, then link it from your account.', true);
} else if (oauthError === 'oauth_denied') {
    showNotice('Social sign-in was cancelled. Please try again when ready.', true);
} else if (oauthError === 'oauth_failed') {
    showNotice('Social sign-in could not be completed. Please try again.', true);
}
