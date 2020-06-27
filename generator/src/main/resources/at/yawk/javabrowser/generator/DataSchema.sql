create domain realm_id as int2 check ( value = 0 or value = 1 );
create domain artifact_id as int2;
create domain source_file_id as int4;
create domain binding_id as int8;

-- ARTIFACTS

create table artifact
(
    artifact_id          serial2 not null primary key,
    string_id            varchar not null,
    last_compile_version integer not null default 0,
    metadata             bytea   not null default '{}'
);

-- SOURCE FILES

create table source_file
(
    realm          realm_id       not null,
    artifact_id    artifact_id    not null,
    source_file_id source_file_id not null,
    path           varchar        not null,
    text           bytea          not null,
    annotations    bytea          not null
)
    partition by list (realm);

create table _source_file0 partition of source_file for values in (0);
create table _source_file1 partition of source_file for values in (1);

-- own table because for large source files there may be multiple of these (tsvector is limited to 16k positions)
-- todo: partition by realm
create table source_file_lexemes_base
(
    realm          realm_id       not null,
    artifact_id    artifact_id    not null,
    source_file_id source_file_id not null,
    lexemes        tsvector       not null,
    starts         int4[]         not null,
    lengths        int4[]         not null,
    primary key (realm, source_file_id)
);

-- separate tables for the separate indices
create table source_file_lexemes
(
) inherits (source_file_lexemes_base);
create table source_file_lexemes_no_symbols
(
) inherits (source_file_lexemes_base);

-- BINDINGS

create table binding
(
    binding_id             binding_id     not null,
    -- null for the top-level package or when the parent can't be resolved or for bytecode classes.
    parent                 binding_id     null,
    source_file_id         source_file_id null,     -- null for packages without package-info
    artifact_id            artifact_id    not null,
    realm                  realm_id       not null,
    include_in_type_search bool           not null,
    binding                varchar        not null,
    description            bytea          not null, -- json
    modifiers              int4           not null
)
    partition by list (realm);

create table _binding0 partition of binding for values in (0);
create table _binding1 partition of binding for values in (1);

-- BINDING REFERENCES

create table binding_reference
(
    realm              realm_id       not null,
    source_artifact_id artifact_id    not null,
    source_file_id     source_file_id not null,
    target             binding_id     not null,
    type               int            not null,
    source_file_line   int            not null,
    source_file_ref_id int            not null
)
    partition by list (realm);

create table _binding_reference0 partition of binding_reference for values in (0);
create table _binding_reference1 partition of binding_reference for values in (1);

-- DEPENDENCIES

create table dependency
(
    from_artifact artifact_id not null references artifact on delete cascade,
    to_artifact   varchar     not null,

    primary key (from_artifact, to_artifact)
);

-- ALIASES

create table artifact_alias
(
    artifact_id artifact_id not null references artifact on delete cascade,
    alias       varchar     not null,

    primary key (artifact_id, alias)
);

-- BINDING REFERENCE COUNTS

create materialized view binding_reference_count_view as
select realm, target, type, source_artifact_id, count(*) as count
from binding_reference
group by (realm, target, type, source_artifact_id);

create materialized view binding_descendant_count_view as
select b1.realm, b1.artifact_id, b1.binding_id, count(b2) as count
from binding b1
inner join binding b2 on b1.realm = b2.realm and b1.artifact_id = b2.artifact_id and b1.binding_id = b2.parent
group by b1.realm, b1.artifact_id, b1.binding_id;
