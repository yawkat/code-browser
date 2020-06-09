-- SOURCE FILES
create index if not exists source_file_index on sourceFiles (artifactId, path);
create index if not exists sourceFileLexemes_lexemes on sourceFileLexemes using gin(lexemes);
create index if not exists sourceFileLexemesNoSymbols_lexemes on sourceFileLexemesNoSymbols using gin(lexemes);

-- BINDINGS
create index if not exists binding0_index on _bindings0 (binding);
create index if not exists binding1_index on _bindings1 (binding);

-- BINDING REFERENCES
create index if not exists binding_references0_targetbinding_type_sourceartifactid_idx on _binding_references0 (targetBinding, type, sourceArtifactId);
create index if not exists binding_references1_targetbinding_type_sourceartifactid_idx on _binding_references1 (targetBinding, type, sourceArtifactId);
-- Due to a planner limitation ( https://commitfest.postgresql.org/15/1124/ ), postgres is currently unable to
-- sort by (type, sourceArtifactId, sourceFile, sourceFileId) using the index over
-- (targetBinding, type, sourceArtifactId). For this reason we add this new index.
create index if not exists binding_references0_targetbinding_type_sourceartifactid_s_idx on _binding_references0 (targetBinding, type, sourceArtifactId, sourceFile, sourceFileId);
create index if not exists binding_references1_targetbinding_type_sourceartifactid_s_idx on _binding_references1 (targetBinding, type, sourceArtifactId, sourceFile, sourceFileId);

-- BINDING REFERENCE COUNTS
create unique index if not exists binding_references_count_view_targetbinding_type_sourcearti_idx on binding_references_count_view (targetbinding, type, sourceArtifactId);

-- PACKAGES
create unique index if not exists packages_artifactId_name_idx on packages_view (artifactId, name);

-- TYPE COUNT BY PACKAGE
create unique index if not exists type_count_by_depth_artifactId_name_depth_idx on type_count_by_depth_view (artifactId, package, depth);