-- Reference MySQL 8 schema for the booking service.
-- Not run automatically (the JPA entities mirror it); apply by hand or via a migration tool later.
-- All DATETIME values are UTC.

create table host (
  id                bigint primary key,
  name              varchar(255) not null,
  email             varchar(255) not null unique,
  timezone          varchar(64)  not null,
  slot_minutes      int not null default 30 check (slot_minutes > 0),
  gap_minutes       int not null default 0  check (gap_minutes >= 0),
  max_per_day       int not null default 4  check (max_per_day > 0),
  availability      json not null,
  calendar_provider varchar(64),
  calendar_ref      varchar(255)
) engine=InnoDB default charset=utf8mb4;

create table `user` (
  id       bigint primary key,
  name     varchar(255) not null,
  email    varchar(255) not null unique,
  host_id  bigint,
  constraint fk_user_host foreign key (host_id) references host(id),
  index idx_user_host (host_id)
) engine=InnoDB default charset=utf8mb4;

create table slots (
  id          bigint auto_increment primary key,
  host_id     bigint not null,
  start_utc   datetime(6) not null,
  end_utc     datetime(6) not null,
  local_date  date not null,
  status      varchar(16) not null default 'OPEN' check (status in ('OPEN','BOOKED','BLOCKED')),
  constraint fk_slots_host foreign key (host_id) references host(id),
  unique key uq_slots_host_start (host_id, start_utc),
  index idx_slots_host_status_start (host_id, status, start_utc),
  index idx_slots_host_local_date (host_id, local_date),
  check (end_utc > start_utc)
) engine=InnoDB default charset=utf8mb4;

create table bookings (
  id                bigint auto_increment primary key,
  slot_id           bigint not null,
  host_id           bigint not null,
  user_id           bigint not null,
  slot_start        datetime(6) not null,
  slot_end          datetime(6) not null,
  status            varchar(16) not null default 'CONFIRMED'
                    check (status in ('CONFIRMED','CANCELLED','RESCHEDULED')),
  user_timezone     varchar(64),
  rescheduled_from  bigint,
  conflict_flag     boolean not null default false,
  manage_token_hash varchar(255),
  host_event_id     varchar(255),
  user_event_id     varchar(255),
  -- = slot_id while CONFIRMED, NULL otherwise. MySQL has no partial indexes; NULLs do not
  -- collide in a unique index, so this enforces one active booking per slot.
  active_slot_id    bigint,
  idempotency_key   varchar(255),
  created_at        datetime(6) not null default current_timestamp(6),
  cancelled_at      datetime(6),
  constraint fk_bookings_slot foreign key (slot_id) references slots(id),
  constraint fk_bookings_host foreign key (host_id) references host(id),
  constraint fk_bookings_user foreign key (user_id) references `user`(id),
  constraint fk_bookings_resched foreign key (rescheduled_from) references bookings(id),
  unique key uq_bookings_active_slot (active_slot_id),
  unique key uq_bookings_idempotency (idempotency_key),
  index idx_bookings_host_start (host_id, slot_start),
  index idx_bookings_user (user_id)
) engine=InnoDB default charset=utf8mb4;
