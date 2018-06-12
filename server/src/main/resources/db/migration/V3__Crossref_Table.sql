create table binding_references (
  targetBinding    varchar not null,
  type             int     not null,
  sourceArtifactId varchar not null,
  sourceFile       varchar not null,
  sourceFileLine   int     not null,
  sourceFileId     int     not null,

  foreign key (sourceArtifactId) references artifacts,
  foreign key (sourceArtifactId, sourceFile) references sourceFiles,
  primary key (sourceArtifactId, sourceFile, sourceFileId)
);

create index on binding_references (targetBinding, type);