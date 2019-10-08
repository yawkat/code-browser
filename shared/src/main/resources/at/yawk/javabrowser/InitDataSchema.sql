-- NOTE: these tables do not have schema information, this script may be run in different schemas on the same db.

-- ARTIFACTS

create table if not exists artifacts
(
    id                 varchar primary key,
    lastCompileVersion integer not null default 0
);
alter table artifacts
    add column if not exists metadata bytea not null default '{}';

-- SOURCE FILES

create table if not exists sourceFiles
(
    artifactId varchar references artifacts,
    path       varchar not null,
    json       bytea   not null,
    primary key (artifactId, path)
);

-- BINDINGS

create table if not exists bindings
(
    artifactId  varchar references artifacts,
    binding     varchar not null,
    description bytea   null, -- json
    parent      varchar null,
    sourceFile  varchar not null,
    isType      bool    not null,
    modifiers   int4    not null, -- at.yawk.javabrowser.BindingDecl#modifiers
    primary key (artifactId, binding),
    foreign key (artifactId, sourceFile) references sourceFiles,
    foreign key (artifactId, parent) references bindings
);

-- BINDING REFERENCES

create table if not exists binding_references
(
    targetBinding    varchar not null,
    type             int     not null,
    sourceArtifactId varchar not null,
    sourceFile       varchar not null,
    sourceFileLine   int     not null,
    sourceFileId     int     not null,

    foreign key (sourceArtifactId, sourceFile) references sourceFiles,
    primary key (sourceArtifactId, sourceFile, sourceFileId)
);

-- DEPENDENCIES

create table if not exists dependencies
(
    fromArtifactId varchar references artifacts,
    toArtifactId   varchar,

    primary key (fromArtifactId, toArtifactId)
);

-- BINDING REFERENCE COUNTS

create materialized view if not exists binding_references_count_view as
select targetBinding, type, sourceArtifactId, count(*) as count
from binding_references
group by (targetBinding, type, sourceArtifactId);

-- PACKAGES

create or replace function count_dots(text)
    returns int
as 'select char_length($1) - char_length(replace ($1, ''.'', ''''));'
    language sql
    immutable
    returns null on null input ;

create or replace function until_last_dot(text)
    returns text
as 'select array_to_string((string_to_array($1, ''.''))[1:array_length(string_to_array($1, ''.''), 1) - 1], ''.'');'
    language sql
    immutable
    returns null on null input ;

create materialized view if not exists packages_view as
with recursive rec (artifactId, name) as (
    select artifactId, until_last_dot(binding) as name from bindings where parent is null
    union distinct
    select artifactId, null from bindings
    union distinct
    select artifactId, until_last_dot(name)
    from rec where position('.' in name) != 0
)
select * from rec
order by artifactId, name;

-- TYPE COUNT BY PACKAGE

-- This view counts the number of direct and indirect members of all packages.
-- The package column may be NULL to denote the "top-level" package.
create materialized view if not exists type_count_by_depth_view as
with types_and_packages (artifactId, name) as (
    select artifactId, binding as name from bindings where parent is null
    union distinct
    select artifactId, name from packages_view
)
select outer_.artifactId, outer_.name package, count_dots(inner_.name) - coalesce(count_dots(outer_.name), -1) depth, count(inner_.name) typeCount
from types_and_packages as outer_
         inner join types_and_packages as inner_
                    on outer_.artifactId = inner_.artifactId and (outer_.name is null or inner_.name like outer_.name || '.%')
group by outer_.artifactId, outer_.name, count_dots(inner_.name);