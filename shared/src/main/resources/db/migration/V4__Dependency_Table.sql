create table dependencies (
  fromArtifactId varchar references artifacts,
  toArtifactId   varchar,

  primary key (fromArtifactId, toArtifactId)
);