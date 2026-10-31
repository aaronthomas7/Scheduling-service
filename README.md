# Scheduling Service

Self-service booking: hosts publish weekly availability, people pick an open slot, and no slot is ever booked twice.

Stack: Spring Boot 3.5.7, Java 17, Spring Data JPA, MySQL 8 (connection to be configured).

## Data model

Four tables. Host and user rows are prefilled; slots and bookings are written by the service.
Reference DDL: [`src/main/resources/db/mysql-schema.sql`](src/main/resources/db/mysql-schema.sql).
Entities: `src/main/java/com/example/demo/model`.

```
host 1 ──< user
host 1 ──< slots 1 ──< bookings >── 1 user
```

| Table | Purpose |
|---|---|
| `host` | Host metadata plus scheduling config: `timezone`, `slot_minutes`, `gap_minutes`, `max_per_day`, and weekly `availability` (JSON, host-local time). |
| `user` | The person who books (invitee). Has an optional assigned `host_id`. |
| `slots` | **One row per slot**, generated from the host's availability. `status` is OPEN, BOOKED or BLOCKED. |
| `bookings` | A user's booking of a slot, with status, calendar event ids, cancel/reschedule token hash and idempotency key. |

### Design decisions

1. **One row per slot, not one JSON per host-day.** Two people booking different slots touch different rows, so they don't block each other. A JSON blob per day means every booking rewrites the same value, which causes lost updates or a lock on the whole day. The database also can't enforce uniqueness inside JSON.
2. **No double booking, enforced by the database.** `unique (host_id, start_utc)` on `slots`, and a unique index on `bookings.active_slot_id`. MySQL has no partial indexes, so `active_slot_id` equals the slot id while the booking is CONFIRMED and is NULL otherwise (the entity sets it automatically). NULLs don't collide in a unique index, so any number of cancelled rows are allowed but only one confirmed booking per slot. The loser of a race gets a duplicate-key error, which the API maps to "This time was just taken". Cancelling or rescheduling clears the value, which frees the slot.
3. **UTC instants plus IANA zones.** Slot and booking times are stored as UTC instants. Availability rules are host-local wall-clock time, so DST doesn't shift them. The viewer's zone is applied only when rendering.
4. **Daily cap.** `slots.local_date` is the date in the host's zone. In the booking transaction, lock the host row (`HostRepository.findByIdForUpdate`, a `SELECT ... FOR UPDATE`), count confirmed bookings for that date (`BookingRepository.countByHostAndLocalDate`), and reject if it reaches `host.max_per_day`. The lock only serializes bookings for that one host, and only for the length of the transaction.
5. **Booking stores `slot_start` and `slot_end` itself.** History survives if slots are regenerated after a config change. Regenerate only future OPEN slots; never touch BOOKED ones.
6. **Calendar changes after booking.** If the host adds a meeting over a confirmed booking (for example at 11:30), the booking is kept and `conflict_flag` is set. The host is notified and can move their meeting or ask the user to reschedule. Silently cancelling is worse than a host conflict. The slot is never offered again and still counts toward the daily cap.
7. **Cancel/reschedule** is done through a link authenticated by `manage_token_hash`, allowed up to 2 hours before the slot. Reschedule books the new slot and cancels the old one in the same transaction, linked by `rescheduled_from`.

### Example

Host in India, 10:00-13:00, 30-minute slots, 15-minute gap, max 4 a day gives slots at 10:00, 10:45, 11:30 and 12:15 (IST). A viewer in London in January sees the 10:45 IST slot as 05:15.

### Planned additions (not yet built)

- `host_busy_cache(host_id, start_utc, end_utc, event_id)`: mirror of the host's calendar, refreshed by webhook plus polling, used to hide slots that clash with the host's own meetings.
- `outbox`: reliable sending of calendar invites and emails after commit.
- Cache of open slots per host (for example Redis, 5-10 second TTL) so a burst of 2,000 viewers doesn't hit the database. Stale cache is safe, because the booking transaction is the source of truth.

## API

### `POST /publish-availability`

Sets a host's weekly availability, slot length, gap and daily cap, then regenerates their future slots. The host row must already exist (hosts are prefilled). A trailing slash (`/publish-availability/`) also works.

```json
{
  "hostId": 1,
  "timezone": "Asia/Kolkata",
  "slotMinutes": 30,
  "gapMinutes": 15,
  "maxPerDay": 4,
  "availability": [{ "days": [1, 2, 3, 4, 5], "start": "10:00", "end": "13:00" }]
}
```

`days` are ISO weekdays (1 = Monday). `start`/`end` are the host's local time and must be on the same day. `gapMinutes` defaults to 0.

The response echoes the saved schedule and shows the result: `slotsGeneratedUntil`, `openSlots` (count) and `nextOpenSlots`, the first 10 with UTC `start`/`end` plus `hostLocalStart`/`hostLocalEnd`. For the body above, Monday's slots are 10:00, 10:45, 11:30 and 12:15 IST.

Behaviour:
- Slots are generated for today plus `scheduling.horizon-days` (default 30) in the host's timezone. Only slots that haven't started are touched.
- **BOOKED slots are never changed.** A future OPEN/BLOCKED slot whose start is still on the new grid is kept and resized. If its start is off the grid, it is deleted, or set to BLOCKED if a booking (even a cancelled one) still references it.
- A slot on the grid is OPEN unless a `SlotBlockingRule` blocks it. `BookedSlotOverlapRule` blocks slots that overlap an existing booking (possible after changing the slot length), so the host can't be double-booked. Adding a reason to block, such as the host's calendar being busy, means adding another rule bean; nothing else changes.
- Runs under the host row lock (`findByIdForUpdate`), the same lock the booking transaction takes.
- Publishing the same schedule twice changes nothing.
- Errors, as `{"error": "...", "message": "..."}`:
  - `400 INVALID_AVAILABILITY` for an unknown timezone, a window that ends before it starts or is shorter than one slot, days outside 1-7, or overlapping windows on the same day.
  - `400 VALIDATION_FAILED` for missing or out-of-range fields.
  - `404 HOST_NOT_FOUND` if the host doesn't exist.

Code layout:
- `AvailabilityController`: HTTP only.
- `AvailabilityService`: validates the request, locks and updates the host, returns the response.
- `SlotRegenerator`: syncs the host's slot rows.
- `SlotGenerator`: pure slot math.
- `service/blocking`: the blocking rules.
- Domain exceptions are mapped to HTTP in `ApiExceptionHandler`.

Not yet: rolling the horizon forward each day (needs a scheduled job), and a rule that blocks slots covered by the host's own calendar (needs `host_busy_cache`).

### `GET /get-available-slots/{hostIds}?tz=Europe/London&from=2026-11-02&to=2026-11-08`

The times a person opening the booking link can pick. `hostIds` is one id or a comma-separated list (a hiring-drive link). `from`/`to` are dates in the viewer's timezone `tz`; they default to today plus 6 days.

A time is listed if its slot is OPEN, in the future, and the host's day (in the host's timezone) is still under `maxPerDay`. Each time appears once, with every host free then and that host's `slotId`:

```json
{"timezone": "Europe/London", "from": "2026-11-02", "to": "2026-11-08",
 "hosts": [{"hostId": 1, "name": "Priya Sharma", "timezone": "Asia/Kolkata"}],
 "slots": [{"start": "2026-11-02T05:15:00Z", "end": "2026-11-02T05:45:00Z", "localDate": "2026-11-02",
            "localStartTime": "05:15", "localEndTime": "05:45", "hosts": [{"hostId": 1, "slotId": 2}]}]}
```

### `POST /book-slot`

Books a time. `date`/`startTime`/`endTime` are in the viewer's `timezone`, as listed by the endpoint above. The email must belong to an invited (prefilled) user.

```json
{"hostIds": [1], "date": "2026-11-02", "startTime": "05:15", "endTime": "05:45",
 "timezone": "Europe/London", "email": "alex@example.com"}
```

`201` returns the booking (`BookingResponse`, the same shape as reschedule). It runs in one transaction under the host row lock, then the slot row lock, the same order as reschedule:
- The slot must still be OPEN and in the future.
- The day must be under the cap.
- The person can't already hold an upcoming booking with this host; they should reschedule it instead.
- Then the slot becomes BOOKED and a CONFIRMED booking is written.

Two people clicking the same time are serialised by the locks: one gets it, the other gets `409 SLOT_TAKEN` "This time was just taken" with `openSlots`. The unique `active_slot_id` index is the backstop. With several `hostIds`, hosts are tried in random order and the first one free at that time gets the booking, which spreads a burst across the pool.

Errors:
- `409 SLOT_TAKEN`, `409 DAY_FULL`: both include `openSlots`.
- `409 ALREADY_BOOKED`
- `404 NOT_FOUND`: the user isn't invited, or the host doesn't exist.
- `400 BAD_REQUEST` / `VALIDATION_FAILED`

### `GET /api/hosts/{hostId}/bookings`

All of the host's bookings in any status, earliest first, with the user's name and email. Read straight from the bookings table, so a new booking or a reschedule (`POST /api/hosts/{hostId}/bookings/reschedule`) shows up immediately. The host page polls this.

## Pages

Plain HTML/JS in `src/main/resources/static`, served by the app:
- `/host.html`: publish availability, and a **Bookings** list that refreshes every 10 seconds.
- `/book.html?hosts=1`: the user's booking page.
  - Shows open times in the viewer's timezone, a week at a time.
  - Pick a time, enter the invited email, and book.
  - Afterwards, pick another time and choose "Move my booking here", which calls reschedule.
  - If someone else got the time first, the page shows "This time was just taken" and reloads the open times.
- `/index.html`: links to both pages.

Shared modules:
- `js/api.js`: the only place that calls `fetch`.
- `js/zones.js`: timezones and date formatting.
- `js/dom.js`: builds elements from text only, never `innerHTML`.
- `js/week.js`: turns the weekly hours into availability rules.

## Database connection

MySQL is the explicit `mysql` profile ([`application-mysql.properties`](src/main/resources/application-mysql.properties)). Run with `SPRING_PROFILES_ACTIVE=mysql`, and set `MYSQL_URL`, `MYSQL_USER` and `MYSQL_PASSWORD`; the defaults point at `localhost:3306/scheduling` as `root`. **Any deployment must set this profile.** With no profile, the app falls back to `local` (in-memory H2, see below) and logs a warning at startup. All datetimes are stored as UTC `DATETIME(6)`, so keep the connection on `serverTimezone=UTC`. Requires MySQL 8.0.16+ for `CHECK` constraints to be enforced. `ddl-auto=validate`, so the schema must exist first; apply `mysql-schema.sql`, or move to Flyway/Liquibase later. Tests run against in-memory H2 in MySQL mode (see `src/test/resources/application.properties`).

## Build and test

```
./mvnw test
```

## Run locally

No MySQL needed. `local` is the default profile: an in-memory H2 database (MySQL mode). The schema is created from the entities. Prefilled data:
- hosts 1-3, with no availability yet;
- invited users `alex@`, `sam@`, `jordan@`, `taylor@` and `riya@example.com`.

Either press Run on `DemoApplication` in your IDE, or:

```
./mvnw spring-boot:run
```

Then:
1. Open `http://localhost:8080/host.html` and publish host 1's hours.
2. Open `http://localhost:8080/book.html?hosts=1` in another tab and book as `alex@example.com`.
3. The booking appears in the host page's Bookings list.

Or publish from the command line:

```
curl -X POST localhost:8080/publish-availability -H 'Content-Type: application/json' \
  -d '{"hostId":1,"timezone":"Asia/Kolkata","slotMinutes":30,"gapMinutes":15,"maxPerDay":4,"availability":[{"days":[1,2,3,4,5],"start":"10:00","end":"13:00"}]}'
```

Data is lost when the app stops. The tables can be browsed at `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:scheduling`, user `sa`, no password).
