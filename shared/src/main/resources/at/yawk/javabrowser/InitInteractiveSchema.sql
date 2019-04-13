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