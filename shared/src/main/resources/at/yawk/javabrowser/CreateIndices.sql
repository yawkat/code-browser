-- SOURCE FILES
create index if not exists source_file_index on sourceFiles (artifactId, path);

-- BINDINGS
create index if not exists binding_index on bindings (binding);
drop index if exists bindings_artifactid_binding_idx;

-- BINDING REFERENCES
-- this index is a duplicate of binding_references_pkey
drop index if exists binding_references_sourceartifactid_sourcefile_sourcefileid_idx;
create index if not exists binding_references_targetbinding_type_sourceartifactid_idx on binding_references (targetBinding, type, sourceArtifactId);
-- don't need this one anymore
drop index if exists binding_references_targetbinding_type_idx;
-- Due to a planner limitation ( https://commitfest.postgresql.org/15/1124/ ), postgres is currently unable to
-- sort by (type, sourceArtifactId, sourceFile, sourceFileId) using the index over
-- (targetBinding, type, sourceArtifactId). For this reason we add this new index.
create index if not exists binding_references_targetbinding_type_sourceartifactid_sour_idx on binding_references (targetBinding, type, sourceArtifactId, sourceFile, sourceFileId);

-- BINDING REFERENCE COUNTS
create unique index if not exists binding_references_count_view_targetbinding_type_sourcearti_idx on binding_references_count_view (targetbinding, type, sourceArtifactId);

-- PACKAGES
create unique index if not exists packages_artifactId_name_idx on packages_view (artifactId, name);

-- TYPE COUNT BY PACKAGE
create unique index if not exists type_count_by_depth_artifactId_name_depth_idx on type_count_by_depth_view (artifactId, package, depth);