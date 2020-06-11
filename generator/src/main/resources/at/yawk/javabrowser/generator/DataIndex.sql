alter table _sourceFiles0 add primary key (artifactId, path);
alter table _sourceFiles1 add primary key (artifactId, path);
alter table _sourceFiles0 add foreign key (artifactId) references artifacts;
alter table _sourceFiles1 add foreign key (artifactId) references artifacts;
alter table sourceFileLexemes add foreign key (realm, artifactId, sourceFile) references sourceFiles;
alter table sourceFileLexemesNoSymbols add foreign key (realm, artifactId, sourceFile) references sourceFiles;
create index on sourceFileLexemes using gin(lexemes);
create index on sourceFileLexemesNoSymbols using gin(lexemes);

alter table _bindings0 add foreign key (artifactId) references artifacts;
alter table _bindings1 add foreign key (artifactId) references artifacts;
alter table _bindings0 add foreign key (realm, artifactId, sourceFile) references sourceFiles;
alter table _bindings1 add foreign key (realm, artifactId, sourceFile) references sourceFiles;
alter table _bindings0 add foreign key (realm, artifactId, parent) references bindings;
alter table _bindings1 add foreign key (realm, artifactId, parent) references bindings;
alter table _bindings0 add primary key (binding, artifactId) include (sourceFile);
alter table _bindings1 add primary key (binding, artifactId) include (sourceFile);

alter table _binding_references0 add primary key (realm, sourceArtifactId, sourceFile, sourceFileId);
alter table _binding_references1 add primary key (realm, sourceArtifactId, sourceFile, sourceFileId);
alter table _binding_references0 add foreign key (realm, sourceArtifactId, sourceFile) references sourceFiles;
alter table _binding_references1 add foreign key (realm, sourceArtifactId, sourceFile) references sourceFiles;
create index on _binding_references0 (targetBinding, type, sourceArtifactId);
create index on _binding_references1 (targetBinding, type, sourceArtifactId);
-- Due to a planner limitation ( https://commitfest.postgresql.org/15/1124/ ), postgres is currently unable to
-- sort by (type, sourceArtifactId, sourceFile, sourceFileId) using the index over
-- (targetBinding, type, sourceArtifactId). For this reason we add this new index.
create index binding_references0_targetbinding_type_sourceartifactid_s_idx on _binding_references0 (targetBinding, type, sourceArtifactId, sourceFile, sourceFileId);
create index binding_references1_targetbinding_type_sourceartifactid_s_idx on _binding_references1 (targetBinding, type, sourceArtifactId, sourceFile, sourceFileId);

create unique index on binding_references_count_view (targetBinding, type, sourceArtifactId);
create unique index on packages_view (artifactId, name);
create unique index on type_count_by_depth_view (artifactId, package, depth);
