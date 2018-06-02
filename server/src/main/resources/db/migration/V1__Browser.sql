create table artifacts (
  id                 varchar primary key,
  lastCompileVersion integer not null default 0
);

create table sourceFiles (
  artifactId varchar references artifacts,
  path       varchar not null,
  json       bytea   not null,
  primary key (artifactId, path)
);

create table bindings (
  artifactId varchar references artifacts,
  binding    varchar not null,
  sourceFile varchar not null,
  isType     bool    not null,
  primary key (artifactId, binding),
  foreign key (artifactId, sourceFile) references sourceFiles
);