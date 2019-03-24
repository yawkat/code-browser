create table hits
(
  -- UTC timestamp of this hit counter. Resolution is application-defined, and may be multiple hours.
  timestamp  timestamp not null,
  sourceFile varchar   not null,
  artifactId varchar   not null,
  hits       int8      not null,

  primary key (timestamp, sourceFile, artifactId)
);