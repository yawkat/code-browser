-- this index is a duplicate of binding_references_pkey
drop index binding_references_sourceartifactid_sourcefile_sourcefileid_idx;

create index on binding_references (targetBinding, type, sourceArtifactId);
-- don't need this one anymore
drop index binding_references_targetbinding_type_idx;
