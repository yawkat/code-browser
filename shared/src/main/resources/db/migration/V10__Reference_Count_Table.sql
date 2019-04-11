create materialized view binding_references_count_view as
select targetbinding, type, sourceArtifactId, count(*) as count
from binding_references
group by (targetbinding, type, sourceArtifactId);

create index on binding_references_count_view (targetbinding, type, sourceArtifactId);