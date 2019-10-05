<#ftl strip_text=true>
<#macro showNode node>
  <#if node.parent??><@showNode node=node.parent/></#if>

  <#if node.id?has_content>
    <a href="/${node.id}">${node.idInParent}/</a>
  </#if>
</#macro>