<#ftl strip_text=true>
<#macro showNode node>
  <#-- @ftlvariable name="node" type="at.yawk.javabrowser.server.artifact.ArtifactNode" -->
  <#if node.parent??><@showNode node=node.parent/></#if>

  <#if node.stringId?has_content>
    <a href="/${node.stringId}">${node.idInParent}/</a>
  </#if>
</#macro>