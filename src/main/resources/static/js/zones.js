// Timezone helpers shared by the host and booking pages.

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

const current = (zone) => CURRENT_ZONE_NAME[zone] ?? zone;

export function browserZone() {
  return current(Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
}

/** Fills a <select> with every IANA zone and selects the browser's. */
export function fillTimezoneSelect(select) {
  const selected = browserZone();
  let zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : FALLBACK_ZONES;
  zones = [...new Set([selected, 'UTC', ...zones.map(current)])].sort();
  select.replaceChildren(...zones.map((zone) => new Option(zone.replaceAll('_', ' '), zone, false, zone === selected)));
}

/** "2026-11-30" -> "Mon 30 Nov" in the viewer's locale; a calendar date, so it's formatted in UTC. */
export function formatDate(isoDate) {
  return new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })
    .format(new Date(`${isoDate}T00:00:00Z`));
}

/** An instant as a wall-clock time in a zone, e.g. "5:15 AM". */
export function formatTime(instant, zone) {
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', timeZone: zone })
    .format(new Date(instant));
}

/** The calendar date ("2026-10-31") of an instant in a zone. */
export function dateIn(instant, zone) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(instant));
}

/** Today's date in a zone. */
export function todayIn(zone) {
  return dateIn(Date.now(), zone);
}

export function addDays(isoDate, days) {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
