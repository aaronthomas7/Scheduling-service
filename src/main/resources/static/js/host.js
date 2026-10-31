import { getHostBookings, publishAvailability } from './api.js';
import { el } from './dom.js';
import { copyMondayToWeekdays, defaultWeek, newWindowFor, toAvailabilityRules } from './week.js';
import { dateIn, fillTimezoneSelect, formatDate, formatTime } from './zones.js';

const BOOKINGS_REFRESH_MS = 10_000;

const form = document.getElementById('availability-form');
const fields = {
  hostId: document.getElementById('hostId'),
  timezone: document.getElementById('timezone'),
  slotMinutes: document.getElementById('slotMinutes'),
  gapMinutes: document.getElementById('gapMinutes'),
  maxPerDay: document.getElementById('maxPerDay'),
};
const weekEl = document.getElementById('week');
const publishButton = document.getElementById('publish');
const errorEl = document.getElementById('form-error');
const resultEl = document.getElementById('result');
const bookingsEl = document.getElementById('bookings');
const bookingsUpdatedEl = document.getElementById('bookings-updated');
const bookingLinkEl = document.getElementById('booking-link');
const dayTemplate = document.getElementById('day-row');
const windowTemplate = document.getElementById('window-row');

let week = defaultWeek();

fillTimezoneSelect(fields.timezone);
renderWeek();
loadBookings();
setInterval(() => {
  if (document.visibilityState === 'visible') loadBookings();
}, BOOKINGS_REFRESH_MS);

document.getElementById('copy-monday').addEventListener('click', () => {
  week = copyMondayToWeekdays(week);
  renderWeek();
});
document.getElementById('refresh-bookings').addEventListener('click', loadBookings);
fields.hostId.addEventListener('change', loadBookings);
fields.timezone.addEventListener('change', loadBookings);

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  showError(null);
  const request = {
    hostId: Number(fields.hostId.value),
    timezone: fields.timezone.value,
    slotMinutes: Number(fields.slotMinutes.value),
    gapMinutes: fields.gapMinutes.value === '' ? 0 : Number(fields.gapMinutes.value),
    maxPerDay: Number(fields.maxPerDay.value),
    availability: toAvailabilityRules(week),
  };
  if (request.availability.length === 0) {
    showError('Turn on at least one day with hours.');
    return;
  }
  setBusy(true);
  try {
    renderResult(await publishAvailability(request));
    loadBookings();
  } catch (err) {
    resultEl.hidden = true;
    showError(err.message);
  } finally {
    setBusy(false);
  }
});

// Structural changes (toggle, add, remove, copy) re-render the week; typing a time only updates state,
// so the input keeps focus.
function renderWeek() {
  weekEl.replaceChildren(...week.map(renderDay));
}

function renderDay(day) {
  const row = dayTemplate.content.firstElementChild.cloneNode(true);
  row.classList.toggle('is-off', !day.enabled);

  const toggle = row.querySelector('.day-enabled');
  toggle.checked = day.enabled;
  toggle.addEventListener('change', () => {
    day.enabled = toggle.checked;
    if (day.enabled && day.windows.length === 0) day.windows.push(newWindowFor(day));
    renderWeek();
  });
  row.querySelector('.day-name').textContent = day.name;

  const list = row.querySelector('.windows');
  day.windows.forEach((hours, index) => list.append(renderWindow(day, hours, index)));

  const add = row.querySelector('.add-window');
  add.setAttribute('aria-label', `Add hours on ${day.name}`);
  add.addEventListener('click', () => {
    day.windows.push(newWindowFor(day));
    day.enabled = true;
    renderWeek();
  });
  return row;
}

function renderWindow(day, hours, index) {
  const item = windowTemplate.content.firstElementChild.cloneNode(true);
  const start = item.querySelector('.window-start');
  const end = item.querySelector('.window-end');
  start.value = hours.start;
  end.value = hours.end;
  start.setAttribute('aria-label', `${day.name} from`);
  end.setAttribute('aria-label', `${day.name} until`);
  start.addEventListener('input', () => { hours.start = start.value; });
  end.addEventListener('input', () => { hours.end = end.value; });

  const remove = item.querySelector('.remove-window');
  remove.setAttribute('aria-label', `Remove ${day.name} hours`);
  remove.addEventListener('click', () => {
    day.windows.splice(index, 1);
    if (day.windows.length === 0) day.enabled = false;
    renderWeek();
  });
  return item;
}

function renderResult(r) {
  const byDate = new Map();
  for (const slot of r.nextOpenSlots) {
    if (!byDate.has(slot.hostLocalDate)) byDate.set(slot.hostLocalDate, []);
    byDate.get(slot.hostLocalDate).push(slot);
  }

  const children = [
    el('p', { class: 'eyebrow success' }, 'Published'),
    el('h2', {}, `${r.name}`),
    el('p', { class: 'summary' },
      el('strong', {}, `${r.openSlots} open slots`), ` through ${formatDate(r.slotsGeneratedUntil)}`,
      ` · ${r.slotMinutes} min slots, ${r.gapMinutes} min gap, up to ${r.maxPerDay} a day`),
  ];
  if (byDate.size === 0) {
    children.push(el('p', { class: 'hint' }, 'No open slots in this period. Check your weekly hours.'));
  } else {
    children.push(el('h3', {}, 'Next open slots'));
    children.push(el('dl', { class: 'slot-days' }, ...[...byDate].flatMap(([date, slots]) => [
      el('dt', {}, formatDate(date)),
      el('dd', {}, ...slots.map((s) => el('span', { class: 'chip' }, `${s.hostLocalStart}–${s.hostLocalEnd}`))),
    ])));
    children.push(el('p', { class: 'hint' },
      `Times are in ${r.timezone.replaceAll('_', ' ')}. Showing the first ${r.nextOpenSlots.length}.`));
  }
  resultEl.replaceChildren(...children);
  resultEl.hidden = false;
  resultEl.scrollIntoView({ block: 'nearest' });
}

async function loadBookings() {
  const hostId = Number(fields.hostId.value);
  bookingLinkEl.href = `book.html?hosts=${hostId || 1}`;
  if (!hostId) {
    bookingsEl.replaceChildren(el('p', { class: 'hint' }, 'Enter a host ID to see bookings.'));
    return;
  }
  try {
    renderBookings(await getHostBookings(hostId), fields.timezone.value);
    bookingsUpdatedEl.textContent = `Updated ${new Date().toLocaleTimeString()}`;
  } catch (err) {
    bookingsEl.replaceChildren(el('p', { class: 'error' }, err.message));
  }
}

/** Upcoming confirmed bookings in the host's timezone; moved and cancelled ones are listed underneath. */
function renderBookings(bookings, zone) {
  const now = Date.now();
  const upcoming = bookings.filter((b) => b.status === 'CONFIRMED' && new Date(b.slotEnd).getTime() > now);
  const history = bookings.filter((b) => b.status !== 'CONFIRMED');
  if (upcoming.length === 0 && history.length === 0) {
    bookingsEl.replaceChildren(el('p', { class: 'hint' }, 'No bookings yet.'));
    return;
  }
  const rows = upcoming.map((b) => el('tr', {},
    el('td', {}, formatDate(dateIn(b.slotStart, zone))),
    el('td', { class: 'nowrap' }, `${formatTime(b.slotStart, zone)} – ${formatTime(b.slotEnd, zone)}`),
    el('td', {}, el('div', {}, b.userName), el('div', { class: 'hint' }, b.userEmail)),
    el('td', {}, b.rescheduledFromBookingId ? el('span', { class: 'chip' }, 'Rescheduled') : '',
      b.calendarConflict ? el('span', { class: 'chip chip-warn' }, 'Calendar conflict') : '')));
  const children = [];
  if (upcoming.length > 0) {
    children.push(el('table', { class: 'bookings' },
      el('thead', {}, el('tr', {}, el('th', {}, 'Day'), el('th', {}, 'Time'), el('th', {}, 'Who'), el('th', {}, ''))),
      el('tbody', {}, ...rows)));
  } else {
    children.push(el('p', { class: 'hint' }, 'No upcoming bookings.'));
  }
  if (history.length > 0) {
    children.push(el('p', { class: 'hint' }, `${history.length} earlier booking(s) were moved or cancelled: `
      + history.map((b) => `${b.userName} (${b.status.toLowerCase()})`).join(', ')));
  }
  bookingsEl.replaceChildren(...children);
}

function showError(message) {
  errorEl.textContent = message ?? '';
  errorEl.hidden = !message;
}

function setBusy(busy) {
  publishButton.disabled = busy;
  publishButton.textContent = busy ? 'Publishing…' : 'Publish availability';
}
