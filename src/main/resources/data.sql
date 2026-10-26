-- Example data, loaded at every start after Hibernate has created the schema.
-- Rows are generated with generate_series so the file stays small; ids are random UUIDs, and the
-- scenarios find rows by their business keys (branch name, ISBN, barcode, member email).

-- Branches ------------------------------------------------------------------------------------
-- Central: 20,000 copies (shelf map). North: small (reservations, member limit).
-- Annex: empty, the target of the bulk import.
insert into branch (id, name, city) values
    (gen_random_uuid(), 'Central', 'Mataró'),
    (gen_random_uuid(), 'North', 'Mataró'),
    (gen_random_uuid(), 'Annex', 'Argentona');

-- Authors -------------------------------------------------------------------------------------
-- 300 distinct authors: one per catalogue book, so the first-level cache cannot hide the N+1.
insert into author (id, name, nationality)
select gen_random_uuid(),
       'Author ' || lpad(n::text, 3, '0'),
       (array['Catalan', 'Spanish', 'French', 'Italian', 'English', 'German'])[n % 6 + 1]
from generate_series(1, 300) as n;

-- One author with 20 books, to show the first-level cache answering repeated author lookups.
insert into author (id, name, nationality) values (gen_random_uuid(), 'Prolific Author', 'Catalan');
insert into author (id, name, nationality) values (gen_random_uuid(), 'Bestselling Author', 'English');

-- Subjects ------------------------------------------------------------------------------------
insert into subject (id, name)
select gen_random_uuid(), s
from unnest(array['Novel', 'Poetry', 'History', 'Science', 'Travel', 'Art', 'Music', 'Cooking',
                  'Philosophy', 'Biography', 'Crime', 'Fantasy', 'Science fiction', 'Children',
                  'Economics', 'Law', 'Medicine', 'Sports', 'Theatre', 'Technology']) as s;

-- Books ---------------------------------------------------------------------------------------
-- Books 1-300: book n is written by 'Author n'.
insert into book (id, isbn, title, publication_year, author_id)
select gen_random_uuid(),
       '978-84-' || lpad(n::text, 6, '0'),
       'Title ' || lpad(n::text, 3, '0'),
       1950 + n % 75,
       a.id
from generate_series(1, 300) as n
join author a on a.name = 'Author ' || lpad(n::text, 3, '0');

-- Books 301-320: all by 'Prolific Author'.
insert into book (id, isbn, title, publication_year, author_id)
select gen_random_uuid(),
       '978-84-' || lpad(n::text, 6, '0'),
       'Prolific Title ' || lpad(n::text, 3, '0'),
       1990 + n % 30,
       a.id
from generate_series(301, 320) as n
join author a on a.name = 'Prolific Author';

-- The bestseller: a single copy in North that fifty members want at the same instant.
insert into book (id, isbn, title, publication_year, author_id)
select gen_random_uuid(), '978-84-999999', 'The Last Copy', 2026, a.id
from author a where a.name = 'Bestselling Author';

-- Book n gets 2 to 4 subjects: subjects n, n+1, ... (modulo 20).
insert into book_subject (book_id, subject_id)
select b.id, s.id
from book b
cross join lateral generate_series(0, substring(b.isbn from 8)::int % 3 + 1) as k
join (select id, row_number() over (order by name) - 1 as position from subject) s
    on s.position = (substring(b.isbn from 8)::int + k) % 20;

-- Members -------------------------------------------------------------------------------------
-- 60 members for the scenarios: they start with no loans and no reservations.
insert into member (id, name, email)
select gen_random_uuid(), 'Member ' || lpad(n::text, 2, '0'), 'member' || lpad(n::text, 2, '0') || '@library.test'
from generate_series(1, 60) as n;

-- 500 readers who own the loans and reservations of Central (at most 3 items each).
insert into member (id, name, email)
select gen_random_uuid(), 'Reader ' || lpad(n::text, 3, '0'), 'reader' || lpad(n::text, 3, '0') || '@library.test'
from generate_series(1, 500) as n;

-- Central: 20,000 copies --------------------------------------------------------------------
-- Copy n: book ((n-1) mod 300) + 1; 5 floors of 4,000 copies; 40 shelves of 100 copies per floor.
-- Statuses: n mod 20 = 0 -> ON_LOAN (1,000); n mod 40 = 1 -> RESERVED (500), of which
-- n mod 200 = 1 have already expired (100); n mod 400 = 3 -> WITHDRAWN (50); the rest AVAILABLE.
insert into book_copy (id, branch_id, book_id, barcode, floor_number, shelf, copy_condition, status,
                       reservation_id, reserved_until)
select gen_random_uuid(),
       br.id,
       b.id,
       'C' || lpad(n::text, 6, '0'),
       (n - 1) / 4000 + 1,
       ((n - 1) / 4000 + 1) || '-' || lpad((((n - 1) % 4000) / 100 + 1)::text, 2, '0'),
       (array['NEW', 'GOOD', 'GOOD', 'WORN', 'DAMAGED'])[n % 5 + 1],
       case when n % 20 = 0 then 'ON_LOAN'
            when n % 40 = 1 then 'RESERVED'
            when n % 400 = 3 then 'WITHDRAWN'
            else 'AVAILABLE' end,
       case when n % 40 = 1 then gen_random_uuid() end,
       case when n % 200 = 1 then now() - interval '1 day'
            when n % 40 = 1 then now() + interval '47 hours' end
from generate_series(1, 20000) as n
join branch br on br.name = 'Central'
join book b on b.isbn = '978-84-' || lpad(((n - 1) % 300 + 1)::text, 6, '0');

-- Loans of the ON_LOAN copies: copy n is lent to reader ((n/20 - 1) mod 500) + 1 (two each).
insert into loan (id, member_id, copy_id, started_at, due_at, returned)
select gen_random_uuid(), m.id, c.id, now() - interval '7 days', now() + interval '14 days', false
from book_copy c
join member m on m.email = 'reader' || lpad(((substring(c.barcode from 2)::int / 20 - 1) % 500 + 1)::text, 3, '0') || '@library.test'
where c.status = 'ON_LOAN';

-- Reservations of the RESERVED copies, reusing the reservation id stored on each copy.
-- Copy n is reserved by reader (n/40 mod 500) + 1 (one each).
insert into reservation (id, member_id, quantity, created_at, expires_at, status)
select c.reservation_id, m.id, 1, c.reserved_until - interval '48 hours', c.reserved_until, 'ACTIVE'
from book_copy c
join member m on m.email = 'reader' || lpad(((substring(c.barcode from 2)::int / 40) % 500 + 1)::text, 3, '0') || '@library.test'
where c.status = 'RESERVED';

insert into reservation_copy (reservation_id, copy_id)
select c.reservation_id, c.id from book_copy c where c.status = 'RESERVED';

-- North: the bestseller and 30 ordinary copies, all available -------------------------------
insert into book_copy (id, branch_id, book_id, barcode, floor_number, shelf, copy_condition, status)
select gen_random_uuid(), br.id, b.id, 'N-BEST', 0, '0-NEW', 'NEW', 'AVAILABLE'
from branch br, book b
where br.name = 'North' and b.isbn = '978-84-999999';

insert into book_copy (id, branch_id, book_id, barcode, floor_number, shelf, copy_condition, status)
select gen_random_uuid(), br.id, b.id, 'N' || lpad(n::text, 4, '0'), 1, '1-' || lpad(((n - 1) / 10 + 1)::text, 2, '0'), 'GOOD', 'AVAILABLE'
from generate_series(1, 30) as n
join branch br on br.name = 'North'
join book b on b.isbn = '978-84-' || lpad(n::text, 6, '0');
