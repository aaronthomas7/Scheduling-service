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

## Database connection

Not configured yet. Set `spring.datasource.*` in `application.properties` (a MySQL URL placeholder is there). All datetimes are stored as UTC `DATETIME(6)`, so keep the connection on `serverTimezone=UTC`. Requires MySQL 8.0.16+ for `CHECK` constraints to be enforced. `ddl-auto=validate`, so the schema must exist first; apply `mysql-schema.sql`, or move to Flyway/Liquibase later. Tests run against in-memory H2 in MySQL mode (see `src/test/resources/application.properties`).

## Build and test

```
./mvnw test
```
