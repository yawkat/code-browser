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
    artifactId varchar references artifacts,
    binding    varchar not null,
    sourceFile varchar not null,
    isType     bool    not null,
    primary key (artifactId, binding),
    foreign key (artifactId, sourceFile) references sourceFiles
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
select targetbinding, type, sourceArtifactId, count(*) as count
from binding_references
group by (targetbinding, type, sourceArtifactId);