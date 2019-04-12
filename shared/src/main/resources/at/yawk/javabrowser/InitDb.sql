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
create index if not exists source_file_index on sourceFiles (artifactId, path);

-- BINDINGS

create table if not exists bindings
(
    artifactId varchar references artifacts,
    binding    varchar not null,
    sourceFile varchar not null,
    isType     bool    not null,
    primary key (artifactId, binding),
    foreign key (artifactId, sourceFile) references sourceFiles
);
create index if not exists binding_index on bindings (binding);
drop index if exists bindings_artifactid_binding_idx;

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
-- this index is a duplicate of binding_references_pkey
drop index if exists binding_references_sourceartifactid_sourcefile_sourcefileid_idx;
create index if not exists binding_references_targetbinding_type_sourceartifactid_idx on binding_references (targetBinding, type, sourceArtifactId);
-- don't need this one anymore
drop index if exists binding_references_targetbinding_type_idx;
-- Due to a planner limitation ( https://commitfest.postgresql.org/15/1124/ ), postgres is currently unable to
-- sort by (type, sourceArtifactId, sourceFile, sourceFileId) using the index over
-- (targetBinding, type, sourceArtifactId). For this reason we add this new index.
create index if not exists binding_references_targetbinding_type_sourceartifactid_sour_idx on binding_references (targetBinding, type, sourceArtifactId, sourceFile, sourceFileId);

-- DEPENDENCIES

create table if not exists dependencies
(
    fromArtifactId varchar references artifacts,
    toArtifactId   varchar,

    primary key (fromArtifactId, toArtifactId)
);

-- HITS

create table if not exists hits
(
    -- UTC timestamp of this hit counter. Resolution is application-defined, and may be multiple hours.
    timestamp  timestamp not null,
    sourceFile varchar   not null,
    artifactId varchar   not null,
    hits       int8      not null,

    primary key (timestamp, sourceFile, artifactId)
);

-- BINDING REFERENCE COUNTS

create materialized view if not exists binding_references_count_view as
select targetbinding, type, sourceArtifactId, count(*) as count
from binding_references
group by (targetbinding, type, sourceArtifactId);

create index if not exists binding_references_count_view_targetbinding_type_sourcearti_idx on binding_references_count_view (targetbinding, type, sourceArtifactId);