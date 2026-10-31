import { publishAvailability } from './api.js';
import { copyMondayToWeekdays, defaultWeek, newWindowFor, toAvailabilityRules } from './week.js';

// Used only by browsers without Intl.supportedValuesOf.
const FALLBACK_ZONES = ['UTC', 'Asia/Kolkata', 'Europe/London', 'Europe/Berlin', 'America/New_York',
  'America/Chicago', 'America/Los_Angeles', 'Asia/Singapore', 'Asia/Tokyo', 'Australia/Sydney'];
// Browsers often report old zone names (Chrome says Asia/Calcutta). Show the current one; the server accepts both.
const CURRENT_ZONE_NAME = {
  'Asia/Calcutta': 'Asia/Kolkata',
  'Asia/Saigon': 'Asia/Ho_Chi_Minh',
  'Asia/Katmandu': 'Asia/Kathmandu',
  'Asia/Rangoon': 'Asia/Yangon',
  'Europe/Kiev': 'Europe/Kyiv',
};

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
const dayTemplate = document.getElementById('day-row');
const windowTemplate = document.getElementById('window-row');

let week = defaultWeek();

fillTimezones();
renderWeek();

document.getElementById('copy-monday').addEventListener('click', () => {
  week = copyMondayToWeekdays(week);
  renderWeek();
});

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
  } catch (err) {
    resultEl.hidden = true;
    showError(err.message);
  } finally {
    setBusy(false);
  }
});

function fillTimezones() {
  const current = (zone) => CURRENT_ZONE_NAME[zone] ?? zone;
  const browserZone = current(Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
  let zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : FALLBACK_ZONES;
  zones = [...new Set([browserZone, 'UTC', ...zones.map(current)])].sort();
  fields.timezone.replaceChildren(
    ...zones.map((zone) => new Option(zone.replaceAll('_', ' '), zone, false, zone === browserZone)));
}

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

function showError(message) {
  errorEl.textContent = message ?? '';
  errorEl.hidden = !message;
}

function setBusy(busy) {
  publishButton.disabled = busy;
  publishButton.textContent = busy ? 'Publishing…' : 'Publish availability';
}

/** "2026-11-30" -> "Mon 30 Nov" in the viewer's locale; the date is a calendar day, so format it in UTC. */
function formatDate(isoDate) {
  return new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })
    .format(new Date(`${isoDate}T00:00:00Z`));
}

/** Builds an element with text children only (never innerHTML), so names from the server can't inject markup. */
function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  node.append(...children);
  return node;
}
