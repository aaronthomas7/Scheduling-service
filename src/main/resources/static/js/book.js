import { ApiError, bookSlot, getAvailableSlots, rescheduleBooking } from './api.js';
import { el } from './dom.js';
import { addDays, dateIn, fillTimezoneSelect, formatDate, formatTime, todayIn } from './zones.js';

const DAYS_SHOWN = 7;
// Per-viewer convenience: remembers this browser's booking so "Move my booking here" works after a reload.
const STORAGE_KEY = 'scheduling.booking';

const $ = (id) => document.getElementById(id);
const ui = {
  title: $('title'),
  hostIds: $('hostIds'),
  timezone: $('timezone'),
  prev: $('prev-week'),
  next: $('next-week'),
  range: $('range'),
  banner: $('banner'),
  days: $('days'),
  confirm: $('confirm'),
  confirmHeading: $('confirm-heading'),
  confirmSummary: $('confirm-summary'),
  emailField: $('email-field'),
  email: $('email'),
  confirmButton: $('confirm-button'),
  confirmError: $('confirm-error'),
  done: $('done'),
};

const state = {
  hostIds: [],
  from: null, // first viewer-local date shown
  data: null, // last /get-available-slots response
  selected: null, // the open time the viewer clicked
  booking: null, // this browser's booking, once made
};

fillTimezoneSelect(ui.timezone);
const params = new URLSearchParams(location.search);
ui.hostIds.value = params.get('hosts') ?? params.get('host') ?? '1';
setHosts(ui.hostIds.value);
state.from = todayIn(zone());
load();

$('link-form').addEventListener('submit', (e) => { e.preventDefault(); changeHosts(); });
ui.hostIds.addEventListener('change', changeHosts);
ui.timezone.addEventListener('change', () => {
  state.from = todayIn(zone());
  clearSelection();
  renderDone();
  load();
});
ui.prev.addEventListener('click', () => { state.from = laterOf(todayIn(zone()), addDays(state.from, -DAYS_SHOWN)); load(); });
ui.next.addEventListener('click', () => { state.from = addDays(state.from, DAYS_SHOWN); load(); });
$('cancel-selection').addEventListener('click', clearSelection);
$('confirm-form').addEventListener('submit', (e) => { e.preventDefault(); confirmSelection(); });

function zone() {
  return ui.timezone.value;
}

function changeHosts() {
  setHosts(ui.hostIds.value);
  const url = new URL(location.href);
  url.searchParams.set('hosts', state.hostIds.join(','));
  history.replaceState(null, '', url);
  clearSelection();
  load();
}

function setHosts(text) {
  state.hostIds = [...new Set(text.split(',').map((s) => Number(s.trim())).filter((n) => Number.isInteger(n) && n > 0))];
  state.booking = readBooking();
  renderDone();
}

async function load() {
  showBanner(null);
  ui.prev.disabled = state.from <= todayIn(zone());
  if (state.hostIds.length === 0) {
    ui.days.replaceChildren();
    showBanner('Enter a host ID to see open times.', 'info');
    return;
  }
  const to = addDays(state.from, DAYS_SHOWN - 1);
  ui.range.textContent = `${formatDate(state.from)} – ${formatDate(to)}`;
  try {
    state.data = await getAvailableSlots(state.hostIds, zone(), state.from, to);
  } catch (err) {
    state.data = null;
    ui.days.replaceChildren();
    showBanner(err.message, 'error');
    return;
  }
  const names = state.data.hosts.map((h) => h.name);
  ui.title.textContent = names.length === 1 ? `Book a time with ${names[0]}` : `Book a time with one of ${names.length} hosts`;
  renderDays();
  renderDone();
  if (state.data.slots.length === 0) showBanner('No open times this week. Try the next week.', 'info');
}

function renderDays() {
  const byDate = new Map();
  for (const slot of state.data.slots) {
    if (!byDate.has(slot.localDate)) byDate.set(slot.localDate, []);
    byDate.get(slot.localDate).push(slot);
  }
  const columns = [];
  for (let i = 0; i < DAYS_SHOWN; i++) {
    const date = addDays(state.from, i);
    const slots = byDate.get(date) ?? [];
    columns.push(el('div', { class: 'day-col' },
      el('h3', {}, formatDate(date)),
      ...(slots.length ? slots.map(slotButton) : [el('p', { class: 'hint' }, 'No times')])));
  }
  ui.days.replaceChildren(...columns);
}

function slotButton(slot) {
  // Once booked, only times the same host has open can be used to move the booking.
  const usable = !state.booking || slot.hosts.some((h) => h.hostId === state.booking.hostId);
  const selected = state.selected?.start === slot.start && state.selected?.end === slot.end;
  const button = el('button', { type: 'button', class: 'slot', 'aria-pressed': String(selected), disabled: !usable },
    formatTime(slot.start, zone()),
    slot.hosts.length > 1 ? el('span', { class: 'slot-hosts' }, `${slot.hosts.length} hosts`) : '');
  button.addEventListener('click', () => select(slot));
  return button;
}

function select(slot) {
  state.selected = slot;
  renderDays();
  const when = `${formatDate(slot.localDate)}, ${formatTime(slot.start, zone())} – ${formatTime(slot.end, zone())}`;
  const tz = zone().replaceAll('_', ' ');
  if (state.booking) {
    ui.confirmHeading.textContent = 'Move your booking';
    ui.confirmSummary.textContent = `Move your booking with ${state.booking.hostName} to ${when} (${tz}).`;
    ui.confirmButton.textContent = 'Move my booking here';
    ui.emailField.hidden = true;
  } else {
    const hostNames = slot.hosts.map((h) => hostName(h.hostId));
    const withWhom = hostNames.length === 1 ? hostNames[0] : `one of ${hostNames.length} hosts`;
    ui.confirmHeading.textContent = 'Confirm your booking';
    ui.confirmSummary.textContent = `${when} (${tz}) with ${withWhom}.`;
    ui.confirmButton.textContent = 'Book this time';
    ui.emailField.hidden = false;
  }
  showConfirmError(null);
  ui.confirm.hidden = false;
  ui.confirm.scrollIntoView({ block: 'nearest' });
  if (!state.booking) ui.email.focus();
}

function clearSelection() {
  state.selected = null;
  ui.confirm.hidden = true;
  if (state.data) renderDays();
}

async function confirmSelection() {
  const slot = state.selected;
  if (!slot) return;
  showConfirmError(null);
  if (!state.booking && !ui.email.value.trim()) {
    showConfirmError('Enter the email you were invited with.');
    return;
  }
  setBusy(true);
  try {
    if (state.booking) {
      const option = slot.hosts.find((h) => h.hostId === state.booking.hostId);
      const moved = await rescheduleBooking(state.booking.hostId,
        { userId: state.booking.userId, newSlotId: option.slotId, userTimezone: zone() });
      saveBooking({ ...state.booking, ...moved }, 'Booking moved');
    } else {
      const booked = await bookSlot({
        hostIds: state.hostIds,
        date: slot.localDate,
        startTime: slot.localStartTime,
        endTime: slot.localEndTime,
        timezone: zone(),
        email: ui.email.value.trim(),
      });
      saveBooking({ ...booked, hostName: hostName(booked.hostId) }, 'You’re booked');
    }
    clearSelection();
    await load();
    ui.done.scrollIntoView({ block: 'nearest' });
  } catch (err) {
    if (err instanceof ApiError && (err.code === 'SLOT_TAKEN' || err.code === 'DAY_FULL')) {
      clearSelection();
      await load();
      showBanner(`${err.message}. Here are the remaining open times.`, 'warn');
    } else {
      showConfirmError(err.message);
    }
  } finally {
    setBusy(false);
  }
}

function renderDone() {
  const b = state.booking;
  if (!b) {
    ui.done.hidden = true;
    return;
  }
  const tz = zone();
  const forget = el('button', { type: 'button', class: 'link-button' }, 'Book for someone else');
  forget.addEventListener('click', () => {
    writeStorage(null);
    state.booking = null;
    renderDone();
    if (state.data) renderDays();
  });
  ui.done.replaceChildren(
    el('p', { class: 'eyebrow success' }, b.headline ?? 'Booked'),
    el('h2', {}, `${formatDate(dateIn(b.slotStart, tz))}, ${formatTime(b.slotStart, tz)} – ${formatTime(b.slotEnd, tz)}`),
    el('p', { class: 'summary' }, `With ${b.hostName} · ${tz.replaceAll('_', ' ')}`),
    el('p', { class: 'hint' }, 'To change it, pick another time below and choose “Move my booking here”. '
      + 'Changes are allowed up to 2 hours before the start.'),
    forget);
  ui.done.hidden = false;
}

function hostName(hostId) {
  return state.data?.hosts.find((h) => h.hostId === hostId)?.name ?? `host ${hostId}`;
}

function saveBooking(booking, headline) {
  state.booking = { ...booking, headline };
  writeStorage(state.booking);
  renderDone();
}

/** The stored booking, if it's for one of the hosts in this link and hasn't ended. */
function readBooking() {
  try {
    const b = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
    if (b && state.hostIds.includes(b.hostId) && new Date(b.slotEnd).getTime() > Date.now()) return b;
  } catch {
    // Storage can be unavailable (private mode); the page works without it.
  }
  return null;
}

function writeStorage(booking) {
  try {
    if (booking) localStorage.setItem(STORAGE_KEY, JSON.stringify(booking));
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // See readBooking.
  }
}

function showBanner(message, kind) {
  ui.banner.textContent = message ?? '';
  ui.banner.className = `banner banner-${kind ?? 'info'}`;
  ui.banner.hidden = !message;
}

function showConfirmError(message) {
  ui.confirmError.textContent = message ?? '';
  ui.confirmError.hidden = !message;
}

function setBusy(busy) {
  ui.confirmButton.disabled = busy;
  if (busy) ui.confirmButton.textContent = state.booking ? 'Moving…' : 'Booking…';
  else ui.confirmButton.textContent = state.booking ? 'Move my booking here' : 'Book this time';
}

function laterOf(a, b) {
  return a > b ? a : b;
}
