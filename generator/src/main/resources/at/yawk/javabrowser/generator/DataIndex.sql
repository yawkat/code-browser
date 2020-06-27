alter table source_file add primary key (realm, artifact_id, source_file_id);
create unique index on source_file (realm, artifact_id, path);
alter table source_file add foreign key (artifact_id) references artifact on delete cascade;
alter table source_file_lexemes add foreign key (realm, artifact_id, source_file_id) references source_file on delete cascade;
alter table source_file_lexemes_no_symbols add foreign key (realm, artifact_id, source_file_id) references source_file on delete cascade;
create index on source_file_lexemes using gin(lexemes);
create index on source_file_lexemes_no_symbols using gin(lexemes);

alter table binding add primary key (realm, binding_id, artifact_id) include (source_file_id);
alter table binding add foreign key (realm, artifact_id, source_file_id) references source_file on delete cascade;
-- sometimes bindings may be inserted before their parent, so we can't have this foreign key
-- alter table binding add foreign key (realm, parent, artifact_id) references binding;

create index on _binding0 (artifact_id, parent);
create index on _binding1 (artifact_id, parent);

alter table binding_reference add primary key (realm, source_artifact_id, source_file_id, source_file_ref_id);
alter table binding_reference add foreign key (realm, source_artifact_id, source_file_id) references source_file on delete cascade;
create index on _binding_reference0 (target, type, source_artifact_id);
create index on _binding_reference1 (target, type, source_artifact_id);
-- Due to a planner limitation ( https://commitfest.postgresql.org/15/1124/ ), postgres is currently unable to
-- sort by (type, sourceArtifactId, sourceFile, sourceFileId) using the index over
-- (targetBinding, type, sourceArtifactId). For this reason we add this new index.
create index on _binding_reference0 (target, type, source_artifact_id, source_file_id, source_file_ref_id);
create index on _binding_reference1 (target, type, source_artifact_id, source_file_id, source_file_ref_id);

create unique index on binding_reference_count_view (target, type, source_artifact_id);
--create unique index on packages_view (artifact_id, name);
--create unique index on type_count_by_depth_view (artifact_id, package, depth);
