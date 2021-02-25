create schema if not exists interactive;
alter table if exists public.hits set schema interactive;
create table if not exists interactive.hits
(
    -- UTC timestamp of this hit counter. Resolution is application-defined, and may be multiple hours.
    timestamp  timestamp not null,
    sourceFile varchar   not null,
    artifactId varchar   not null,
    hits       int8      not null,

    primary key (timestamp, sourceFile, artifactId)
);

-- the common_prefix is only used on items from the data schema, but because we use it in the server, we put it in the
-- interactive schema so we can update it easily
create or replace function interactive.common_prefix_iterate(state text, value text)
    returns text
as
$$
select substr(value, 0, len) as new_prefix
from generate_series(0, least(length(state), length(value)) + 1) len
where substr(value, 0, len) = substr(state, 0, len)
order by len desc
limit 1
$$ language 'sql' immutable strict;

create or replace aggregate interactive.common_prefix(text) (
    sfunc = common_prefix_iterate,
    stype = text,
    combinefunc = common_prefix_iterate,
    parallel = safe
);

create or replace aggregate xor(int8) (
    stype = int8,
    sfunc = int8xor,
    mstype = int8,
    msfunc = int8xor,
    minvfunc = int8xor,
    combinefunc = int8xor,
    initcond = '0',
    minitcond = '0',
    parallel = safe
);
