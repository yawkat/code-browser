<#ftl strip_text=true>
<#macro home><a href="/"><i class="fa fa-home"></i> /</a></#macro>
<#macro showNode node>
  <#if node.parent??><@showNode node=node.parent/></#if>

  <#if node.id?has_content>
    <a href="/${node.id}">${node.idInParent}/</a>
  <#else>
    <@home/>
  </#if>
</#macro>