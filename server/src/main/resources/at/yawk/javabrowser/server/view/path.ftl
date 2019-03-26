<#macro showNode node>
  <#if node.parent??><@showNode node=node.parent/></#if>

  <#if node.id?has_content>
    <a href="/${node.id}">${node.idInParent}/</a>
  <#else>
    <a href="/"><i class="fa fa-home"></i> /</a>
  </#if>
</#macro>
<@showNode node=artifactId/>