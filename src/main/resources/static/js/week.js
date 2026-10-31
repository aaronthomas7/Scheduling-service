// Weekly-hours model for the host page. Pure functions, no DOM, so the mapping to the API is easy to follow.

export const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

/** Mon-Fri 10:00-13:00, the problem-statement example. isoDay matches the API (1 = Monday). */
export function defaultWeek() {
  return DAY_NAMES.map((name, i) => ({
    isoDay: i + 1,
    name,
    enabled: i < 5,
    windows: i < 5 ? [{ start: '10:00', end: '13:00' }] : [],
  }));
}

/** A sensible window to add to a day: the working day if it's empty, otherwise the afternoon. */
export function newWindowFor(day) {
  return day.windows.length === 0 ? { start: '09:00', end: '17:00' } : { start: '14:00', end: '17:00' };
}

/** Monday's hours copied onto Tuesday-Friday; the weekend is left alone. */
export function copyMondayToWeekdays(week) {
  const monday = week[0];
  return week.map((day) => (day.isoDay >= 2 && day.isoDay <= 5
    ? { ...day, enabled: monday.enabled, windows: monday.windows.map((w) => ({ ...w })) }
    : day));
}

/**
 * The API's availability rules: days with identical hours share one rule, e.g.
 * Mon-Fri 10:00-13:00 becomes [{days: [1,2,3,4,5], start: "10:00", end: "13:00"}].
 */
export function toAvailabilityRules(week) {
  const byWindow = new Map();
  for (const day of week) {
    if (!day.enabled) continue;
    for (const w of day.windows) {
      const key = `${w.start}-${w.end}`;
      if (!byWindow.has(key)) byWindow.set(key, { days: [], start: w.start, end: w.end });
      byWindow.get(key).days.push(day.isoDay);
    }
  }
  return [...byWindow.values()];
}
